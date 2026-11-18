package com.twofrogs.frog;

import com.twofrogs.TheTwoFrogs;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogLocation;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The navigation brain of an ESCAPED frog. It decides where the frog should hop (EscapedBehavior does the
 * hopping), and it breaks blocks when the route needs it.
 *  - With a partner to reach: plan a route over the real blocks (RoutePlanner), follow it, cruise along
 *    open stretches toward a point several cells ahead, hop straight at the partner at the end.
 *  - A block directly in front on the way to the partner is dug at once.
 *  - Stuck? Wiggle, avoid the cell that failed, and re-plan; after repeated failures explore for a moment.
 *  - With no partner to reach: roam to random nearby spots and rest between walks.
 * Digging is real block removal, one 2x2 opening at a time. Nothing teleports and nothing phases through blocks.
 *
 * All temporary memory below is not saved; it is rebuilt within seconds after a reload.
 */
public class PartnerSeeker {

    private static final Map<UUID, SeekInfo> INFO = new HashMap<>();
    private static final double[] SIDES = {0.3, -0.3};

    private static final class SeekInfo {
        // target
        boolean seeking = false;
        Vec3 target = null;
        BlockPos goalCell = null;

        // route
        List<RoutePlanner.Step> route = null;
        int routeIndex = 0;
        BlockPos routeStart = null;
        BlockPos routeGoal = null;
        boolean routeReachesGoal = false;
        boolean roamRoute = false;
        long routeTick = 0;

        // output for EscapedBehavior
        Vec3 waypoint = null;
        boolean free = false;
        boolean cruise = false;

        // timing
        long idleUntil = 0;
        long exploreUntil = 0;
        long digCooldownUntil = 0;

        // stuck recovery
        Vec3 lastSample = null;
        int stuckSamples = 0;
        int failStreak = 0;
        boolean wiggle = false;

        // progress
        double bestDistance = Double.MAX_VALUE;
        int blocksSinceProgress = 0;

        final Map<BlockPos, Integer> failedAttempts = new HashMap<>();
        final Set<BlockPos> blacklist = new HashSet<>();
        final Map<BlockPos, Long> avoid = new HashMap<>();

        // breaking
        BlockPos breakPos = null;
        List<BlockPos> breakArea = null;
        int breakProgress = 0;
        int breakRequired = 0;
        int lastCrackStage = -1;
    }

    // ---------- Queries used by EscapedBehavior ----------

    public static boolean isBreaking(UUID frogId) {
        SeekInfo info = INFO.get(frogId);
        return info != null && info.breakPos != null;
    }

    /** True while the frog has a real partner to go to (even if it is exploring around an obstacle). */
    public static boolean isSeeking(UUID frogId) {
        SeekInfo info = INFO.get(frogId);
        return info != null && info.seeking;
    }

    /** The spot the frog should hop toward right now, or null if it should rest. */
    public static Vec3 getWaypoint(UUID frogId) {
        SeekInfo info = INFO.get(frogId);
        return info == null ? null : info.waypoint;
    }

    /** True where the frog may deviate a little (to dodge players): open ground, not climbs or digs. */
    public static boolean isFree(UUID frogId) {
        SeekInfo info = INFO.get(frogId);
        return info != null && info.free;
    }

    /** True when the waypoint is a point well ahead on a clear stretch: hop at full strength. */
    public static boolean isCruise(UUID frogId) {
        SeekInfo info = INFO.get(frogId);
        return info != null && info.cruise;
    }

    /** True once after the frog has been stuck: it should do a sideways wiggle hop. */
    public static boolean consumeWiggle(UUID frogId) {
        SeekInfo info = INFO.get(frogId);
        if (info == null || !info.wiggle) {
            return false;
        }
        info.wiggle = false;
        return true;
    }

    // ---------- Main loop (called every tick) ----------

    public static void onServerTick(MinecraftServer server) {
        TwoFrogsState state = TwoFrogsState.get(server);
        int tick = server.getTickCount();
        boolean decisionTick = tick % AiTuning.DECISION_INTERVAL_TICKS == 0;
        boolean stuckTick = tick % AiTuning.STUCK_SAMPLE_TICKS == 0;
        boolean secondTick = tick % 20 == 0;

        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                continue;
            }
            Frog frog = SpecialFrogManager.findFrog(server, uuid.get());
            if (frog == null) {
                continue;
            }
            ServerLevel level = (ServerLevel) frog.level();
            SeekInfo info = INFO.get(uuid.get());

            if (state.getBehavior(role) != FrogBehavior.ESCAPED) {
                // Never seek or dig while dormant or captured.
                if (info != null) {
                    cancelBreak(level, frog, info);
                    INFO.remove(uuid.get());
                }
                continue;
            }
            boolean justStarted = false;
            if (info == null) {
                info = new SeekInfo();
                INFO.put(uuid.get(), info);
                justStarted = true; // a newly escaped frog decides right away
            }

            // An enraged frog hunting a player drops everything else.
            if (RageBehavior.isChasing(uuid.get())) {
                if (info.breakPos != null) {
                    cancelBreak(level, frog, info);
                }
                info.waypoint = null;
                continue;
            }

            long now = level.getGameTime();
            double agitation = state.getAgitation(role);

            if (info.breakPos != null) {
                tickBreaking(level, frog, info);
                info.waypoint = null;
                continue;
            }

            if (decisionTick || justStarted) {
                refreshTarget(server, state, role, frog, level, info);
            }
            if (secondTick) {
                trackProgress(frog, info);
            }

            // Something solid directly ahead? Start digging it right now.
            digInFront(level, frog, info, agitation, now);
            if (info.breakPos != null) {
                info.waypoint = null;
                continue;
            }

            followRoute(level, frog, info, agitation, now);
            if (info.breakPos != null) {
                info.waypoint = null;
                continue;
            }

            if (stuckTick) {
                checkStuck(info, frog, now);
            }
            if (decisionTick || justStarted) {
                decide(level, frog, info, agitation, now);
            }

            updateWaypoint(level, frog, info);
        }
    }

    // ---------- The partner ----------

    private static void refreshTarget(MinecraftServer server, TwoFrogsState state, FrogRole role, Frog frog,
                                      ServerLevel level, SeekInfo info) {
        info.seeking = false;
        info.target = null;
        info.goalCell = null;

        FrogRole partnerRole = role == FrogRole.A ? FrogRole.B : FrogRole.A;
        Optional<UUID> partnerId = state.getFrogUuid(partnerRole);
        if (partnerId.isEmpty()) {
            return;
        }

        Vec3 target;
        BlockPos goal;
        Frog partner = SpecialFrogManager.findFrog(server, partnerId.get());
        if (partner != null) {
            if (partner.level() != level) {
                return; // another dimension
            }
            target = partner.position();
            goal = FrogTerrain.standCell(level, partner);
        } else {
            FrogLocation last = state.getLastKnown(partnerRole);
            if (last == null || !last.dimension().equals(level.dimension().location().toString())) {
                return;
            }
            target = Vec3.atBottomCenterOf(last.pos());
            goal = last.pos();
        }
        if (frog.position().distanceTo(target) > AiTuning.SENSE_RANGE_BLOCKS) {
            return;
        }
        info.seeking = true;
        info.target = target;
        info.goalCell = goal;
    }

    private static void trackProgress(Frog frog, SeekInfo info) {
        if (!info.seeking || info.target == null) {
            return;
        }
        double distance = frog.position().distanceTo(info.target);
        if (distance < info.bestDistance - 0.5) {
            info.bestDistance = distance;
            info.blocksSinceProgress = 0;
            info.failStreak = 0;
        }
    }

    // ---------- Deciding what to do ----------

    private static void decide(ServerLevel level, Frog frog, SeekInfo info, double agitation, long now) {
        if (info.seeking && now >= info.exploreUntil) {
            seek(level, frog, info, agitation, now);
        } else {
            roam(level, frog, info, agitation, now);
        }
    }

    private static void seek(ServerLevel level, Frog frog, SeekInfo info, double agitation, long now) {
        boolean needPlan = info.route == null;
        if (!needPlan) {
            if (info.roamRoute) {
                needPlan = true;  // it was roaming, but now it has a target
            } else if (info.routeGoal == null
                    || info.routeGoal.distManhattan(info.goalCell) > AiTuning.GOAL_MOVED_REPLAN_CELLS) {
                needPlan = true;  // the partner moved
            } else if (!info.routeReachesGoal && now - info.routeTick >= AiTuning.PARTIAL_REPLAN_TICKS) {
                needPlan = true;  // only a partial route: extend it
            } else if (offRoute(frog, info)) {
                needPlan = true;
            }
        }
        if (!needPlan) {
            return;
        }

        Set<BlockPos> blocked = blockedCells(info, now);
        BlockPos start = FrogTerrain.standCell(level, frog);
        boolean allowDig = now >= info.digCooldownUntil;
        RoutePlanner.Route result = RoutePlanner.plan(level, start, info.goalCell, allowDig, agitation, blocked);

        if (result.steps().isEmpty()) {
            clearRoute(info);
            if (!result.reachesGoal()) {
                // No way forward from here: explore around for a moment, then think again.
                info.exploreUntil = now + AiTuning.NO_ROUTE_EXPLORE_TICKS;
                info.idleUntil = 0;
            }
            return;
        }

        setRoute(info, start, result.steps(), result.reachesGoal(), info.goalCell, false, now);

        int digs = 0;
        for (RoutePlanner.Step step : result.steps()) {
            if (step.dig() != null) {
                digs++;
            }
        }
        if (digs > 0) {
            TheTwoFrogs.LOGGER.info("A frog planned a route of {} steps ({} dig).", result.steps().size(), digs);
        }
    }

    /** Wanders to a random nearby spot, rests, and repeats. */
    private static void roam(ServerLevel level, Frog frog, SeekInfo info, double agitation, long now) {
        if (info.route != null && info.roamRoute) {
            return; // already on a walk
        }
        if (info.route != null) {
            clearRoute(info); // an old route toward the partner: forget it while exploring
        }
        if (now < info.idleUntil) {
            return; // resting between walks
        }

        RandomSource random = frog.getRandom();
        BlockPos start = FrogTerrain.standCell(level, frog);
        Set<BlockPos> blocked = blockedCells(info, now);

        for (int i = 0; i < AiTuning.ROAM_TRIES; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = AiTuning.ROAM_MIN_BLOCKS + random.nextInt(AiTuning.ROAM_EXTRA_BLOCKS + 1);
            BlockPos base = new BlockPos(
                    Mth.floor(frog.getX() + Math.cos(angle) * distance),
                    start.getY(),
                    Mth.floor(frog.getZ() + Math.sin(angle) * distance));
            BlockPos goal = FrogTerrain.findStandable(level, base);
            if (goal == null) {
                continue;
            }
            RoutePlanner.Route route = RoutePlanner.plan(level, start, goal, false, agitation, blocked);
            if (route.reachesGoal() && !route.steps().isEmpty()) {
                setRoute(info, start, route.steps(), true, goal, true, now);
                return;
            }
        }
        info.idleUntil = now + AiTuning.ROAM_IDLE_MIN_TICKS + random.nextInt(AiTuning.ROAM_IDLE_EXTRA_TICKS + 1);
    }

    private static boolean offRoute(Frog frog, SeekInfo info) {
        if (info.route == null || info.routeIndex >= info.route.size()) {
            return false;
        }
        BlockPos dest = info.route.get(info.routeIndex).dest();
        double limit = AiTuning.OFF_ROUTE_BLOCKS;
        return frog.distanceToSqr(Vec3.atBottomCenterOf(dest)) > limit * limit;
    }

    private static Set<BlockPos> blockedCells(SeekInfo info, long now) {
        info.avoid.values().removeIf(expiry -> expiry <= now);
        Set<BlockPos> blocked = new HashSet<>(info.blacklist);
        blocked.addAll(info.avoid.keySet());
        return blocked;
    }

    // ---------- Following the route ----------

    private static void setRoute(SeekInfo info, BlockPos start, List<RoutePlanner.Step> steps,
                                 boolean reachesGoal, BlockPos goal, boolean roam, long now) {
        info.route = new ArrayList<>(steps);
        info.routeIndex = 0;
        info.routeStart = start;
        info.routeGoal = goal;
        info.routeReachesGoal = reachesGoal;
        info.roamRoute = roam;
        info.routeTick = now;
        info.stuckSamples = 0;
    }

    private static void clearRoute(SeekInfo info) {
        info.route = null;
        info.routeIndex = 0;
        info.roamRoute = false;
    }

    /** Called every tick: advances along the route as the frog passes each cell, and starts digging when needed. */
    private static void followRoute(ServerLevel level, Frog frog, SeekInfo info, double agitation, long now) {
        if (info.route == null) {
            return;
        }
        while (info.routeIndex < info.route.size()) {
            // Carried past several cells by one hop? Skip ahead (never over a block it still has to dig).
            int skipTo = farthestReached(level, frog, info);
            if (skipTo > info.routeIndex) {
                info.routeIndex = skipTo;
                info.stuckSamples = 0;
            }

            RoutePlanner.Step step = info.route.get(info.routeIndex);

            // A dig step: the block has to be gone before the frog moves on.
            BlockPos dig = step.dig();
            if (dig != null && level.hasChunkAt(dig) && FrogTerrain.solid(level, dig)) {
                if (frog.distanceToSqr(Vec3.atCenterOf(dig)) <= AiTuning.DIG_START_REACH_SQUARED) {
                    if (isOccupied(level, frog, dig)) {
                        routeFailed(info, dig, now);
                        return;
                    }
                    startBreaking(level, frog, info, step, agitation);
                }
                return; // either digging now, or still walking up to the spot (see updateWaypoint)
            }

            if (reachedCell(frog, step.dest())) {
                info.routeIndex++;
                info.stuckSamples = 0;
                continue;
            }
            return;
        }

        // Route finished.
        boolean roam = info.roamRoute;
        clearRoute(info);
        if (roam) {
            RandomSource random = frog.getRandom();
            info.idleUntil = now + AiTuning.ROAM_IDLE_MIN_TICKS + random.nextInt(AiTuning.ROAM_IDLE_EXTRA_TICKS + 1);
        }
    }

    /** The index of the farthest upcoming cell the frog has already passed, or -1. */
    private static int farthestReached(ServerLevel level, Frog frog, SeekInfo info) {
        int from = info.routeIndex;
        int last = Math.min(info.route.size() - 1, from + AiTuning.ROUTE_SKIP_AHEAD_CELLS);
        for (int j = last; j > from; j--) {
            if (!reachedCell(frog, info.route.get(j).dest())) {
                continue;
            }
            boolean blockedByDig = false;
            for (int k = from; k < j; k++) {
                BlockPos dig = info.route.get(k).dig();
                if (dig != null && level.hasChunkAt(dig) && FrogTerrain.solid(level, dig)) {
                    blockedByDig = true;
                    break;
                }
            }
            if (!blockedByDig) {
                return j;
            }
        }
        return -1;
    }

    /** True if the frog is on (or has hopped over) the cell. Being above it counts: it is on its way down. */
    private static boolean reachedCell(Frog frog, BlockPos cell) {
        double dx = frog.getX() - (cell.getX() + 0.5);
        double dz = frog.getZ() - (cell.getZ() + 0.5);
        double radius = AiTuning.ROUTE_REACH_RADIUS;
        return dx * dx + dz * dz <= radius * radius
                && frog.getY() >= cell.getY() - AiTuning.ROUTE_REACH_Y_BELOW
                && frog.getY() <= cell.getY() + AiTuning.ROUTE_REACH_Y_ABOVE;
    }

    private static void routeFailed(SeekInfo info, BlockPos cell, long now) {
        info.avoid.put(cell.immutable(), now + AiTuning.AVOID_TICKS);
        clearRoute(info);
        info.failStreak++;
        if (info.failStreak >= AiTuning.FAIL_STREAK_BEFORE_EXPLORE) {
            info.failStreak = 0;
            info.exploreUntil = now + AiTuning.NO_ROUTE_EXPLORE_TICKS;
            info.idleUntil = 0;
        }
    }

    private static void checkStuck(SeekInfo info, Frog frog, long now) {
        Vec3 pos = frog.position();
        if (info.lastSample != null && info.waypoint != null) {
            if (pos.distanceTo(info.lastSample) < AiTuning.STUCK_MOVE_BLOCKS) {
                info.stuckSamples++;
            } else {
                info.stuckSamples = 0;
            }
        } else {
            info.stuckSamples = 0;
        }
        info.lastSample = pos;

        if (info.stuckSamples >= AiTuning.STUCK_SAMPLES_BEFORE_FAIL) {
            info.stuckSamples = 0;
            info.wiggle = true;
            if (info.route != null && info.routeIndex < info.route.size()) {
                routeFailed(info, info.route.get(info.routeIndex).dest(), now);
            } else {
                clearRoute(info);
            }
        }
    }

    // ---------- Where to hop right now ----------

    private static void updateWaypoint(ServerLevel level, Frog frog, SeekInfo info) {
        info.waypoint = null;
        info.free = false;
        info.cruise = false;

        BlockPos frogCell = FrogTerrain.standCell(level, frog);

        // Final approach: straight at the partner once it is close and nothing is in the way.
        if (info.seeking && info.target != null && info.goalCell != null) {
            double dx = info.target.x - frog.getX();
            double dz = info.target.z - frog.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double dy = info.target.y - frog.getY();
            if (horizontal <= AiTuning.FINAL_APPROACH_BLOCKS && Math.abs(dy) <= 1.6
                    && groundFollowClear(level, frog, frogCell, info.target.x, info.target.z)) {
                info.waypoint = info.target;
                info.free = true;
                return;
            }
        }

        if (info.route == null || info.routeIndex >= info.route.size()) {
            return;
        }
        RoutePlanner.Step step = info.route.get(info.routeIndex);

        // About to dig: first walk up to the cell next to the block.
        BlockPos dig = step.dig();
        if (dig != null && level.hasChunkAt(dig) && FrogTerrain.solid(level, dig)) {
            BlockPos source = info.routeIndex == 0 ? info.routeStart : info.route.get(info.routeIndex - 1).dest();
            info.waypoint = Vec3.atBottomCenterOf(source);
            return;
        }

        // Cruising: aim at the farthest cell ahead that the frog can head for in one go, even over bumps.
        // Climbs, big drops, digs and blocked lines end the lookahead (and are then approached precisely).
        int best = info.routeIndex;
        boolean precise = false;
        int previousY = frogCell.getY();
        int limit = Math.min(info.route.size() - 1, info.routeIndex + AiTuning.SMOOTH_LOOKAHEAD_CELLS);

        for (int j = info.routeIndex; j <= limit; j++) {
            RoutePlanner.Step s = info.route.get(j);
            if (s.kind() != RoutePlanner.StepKind.WALK || s.dig() != null) {
                if (j == info.routeIndex) {
                    precise = true;
                }
                break;
            }
            int change = s.dest().getY() - previousY;
            if (change >= 1 || change <= -2) {
                if (j == info.routeIndex) {
                    best = j;
                    precise = true;
                }
                break;
            }
            if (!groundFollowClear(level, frog, frogCell, s.dest().getX() + 0.5, s.dest().getZ() + 0.5)) {
                if (j == info.routeIndex) {
                    precise = true;
                }
                break;
            }
            best = j;
            previousY = s.dest().getY();
        }

        info.waypoint = Vec3.atBottomCenterOf(info.route.get(best).dest());
        info.free = !precise;
        info.cruise = !precise && best > info.routeIndex;
    }

    /**
     * True if a frog could head in a straight line from where it is to (tx, tz), following the ground
     * (hopping up single blocks and dropping a little) without hitting a wall.
     */
    private static boolean groundFollowClear(ServerLevel level, Frog frog, BlockPos startCell, double tx, double tz) {
        double dx = tx - frog.getX();
        double dz = tz - frog.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01) {
            return true;
        }
        double ux = dx / length;
        double uz = dz / length;
        double px = -uz;
        double pz = ux;
        int y = startCell.getY();
        int samples = (int) Math.ceil(length / 0.5);

        for (int i = 1; i <= samples; i++) {
            double t = Math.min(length, i * 0.5);
            double sx = frog.getX() + ux * t;
            double sz = frog.getZ() + uz * t;
            BlockPos cell = new BlockPos(Mth.floor(sx), y, Mth.floor(sz));
            if (!level.hasChunkAt(cell)) {
                return false;
            }
            if (FrogTerrain.solid(level, cell)) {
                if (FrogTerrain.solid(level, cell.above()) || !FrogTerrain.climbable(level, cell)) {
                    return false; // a wall
                }
                y++; // a single block: hop up onto it
                continue;
            }
            int drop = 0;
            while (drop < 2 && !FrogTerrain.solid(level, cell.below()) && level.getFluidState(cell).isEmpty()) {
                cell = cell.below();
                y--;
                drop++;
            }
            if (!FrogTerrain.solid(level, cell.below()) && level.getFluidState(cell).isEmpty()) {
                return false; // no floor
            }
            for (double side : SIDES) {
                BlockPos lateral = new BlockPos(Mth.floor(sx + px * side), y, Mth.floor(sz + pz * side));
                if (level.hasChunkAt(lateral) && FrogTerrain.solid(level, lateral)
                        && FrogTerrain.solid(level, lateral.above())) {
                    return false; // squeezing past a corner
                }
            }
        }
        return true;
    }

    // ---------- Digging straight through whatever is in front ----------

    /**
     * If a block stands directly in front of the frog on its way to the partner, it starts digging it
     * straight away (no detour, no waiting). A single block it could easily hop onto is left alone
     * (see AiTuning.DIG_HOPPABLE_BLOCKS). Stairs and shafts are left to the route planner.
     */
    private static void digInFront(ServerLevel level, Frog frog, SeekInfo info, double agitation, long now) {
        if (!AiTuning.DIG_IN_FRONT || !info.seeking || info.target == null || info.breakPos != null) {
            return;
        }
        if (now < info.digCooldownUntil || now < info.exploreUntil) {
            return;
        }

        double dx = info.target.x - frog.getX();
        double dz = info.target.z - frog.getZ();
        double dy = info.target.y - frog.getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.5 || Math.abs(dy) > AiTuning.FRONT_DIG_MAX_VERTICAL_BLOCKS) {
            return;
        }

        BlockPos cell = FrogTerrain.standCell(level, frog);
        Direction dir = Direction.getNearest(dx, 0.0, dz);
        BlockPos front = cell.relative(dir);
        if (!level.hasChunkAt(cell) || !level.hasChunkAt(front)) {
            return;
        }

        if (!FrogTerrain.solid(level, front)
                || FrogTerrain.topHeight(level, front) <= AiTuning.FRONT_IGNORE_HEIGHT) {
            return;
        }
        if (!AiTuning.DIG_HOPPABLE_BLOCKS
                && FrogTerrain.climbable(level, front)
                && !FrogTerrain.solid(level, front.above())
                && !FrogTerrain.solid(level, cell.above())) {
            return;
        }
        if (info.blacklist.contains(front)) {
            return;
        }
        if (level.getBlockState(front).getDestroySpeed(level, front) < 0.0F) {
            info.blacklist.add(front.immutable());
            return;
        }

        double ox = frog.getX() - (front.getX() + 0.5);
        double oz = frog.getZ() - (front.getZ() + 0.5);
        if (ox * ox + oz * oz > AiTuning.FRONT_DIG_REACH * AiTuning.FRONT_DIG_REACH) {
            return;
        }
        if (isOccupied(level, frog, front)) {
            return;
        }

        clearRoute(info); // any old route is out of date once the way opens up
        info.exploreUntil = 0;
        startBreaking(level, frog, info,
                new RoutePlanner.Step(RoutePlanner.StepKind.DIG, front, front, dir), agitation);
    }

    // ---------- Breaking ----------

    /** The blocks cleared by one dig: just the target, or a 2x2 opening around it. */
    private static List<BlockPos> areaCells(BlockPos target, Direction dir) {
        List<BlockPos> cells = new ArrayList<>();
        cells.add(target);
        if (FrogTuning.BREAK_AREA_SIZE < 2) {
            return cells;
        }
        if (dir == null) {
            // Digging down: a 2x2 patch of floor.
            cells.add(target.east());
            cells.add(target.south());
            cells.add(target.east().south());
        } else {
            // Digging sideways: a 2 wide by 2 high opening.
            Direction lateral = dir.getClockWise();
            cells.add(target.above());
            cells.add(target.relative(lateral));
            cells.add(target.above().relative(lateral));
        }
        return cells;
    }

    private static void startBreaking(ServerLevel level, Frog frog, SeekInfo info,
                                      RoutePlanner.Step step, double agitation) {
        BlockPos pos = step.dig();
        double seconds = RoutePlanner.breakSeconds(level, pos, agitation);

        info.breakPos = pos.immutable();
        info.breakArea = areaCells(pos, step.dir());
        info.breakProgress = 0;
        info.breakRequired = (int) Math.ceil(seconds * 20.0);
        info.lastCrackStage = -1;
        info.waypoint = null;

        TheTwoFrogs.LOGGER.info("A frog starts breaking {} at {} (about {} seconds).",
                level.getBlockState(pos).getBlock().getDescriptionId(), pos.toShortString(),
                String.format("%.1f", seconds));
    }

    private static void tickBreaking(ServerLevel level, Frog frog, SeekInfo info) {
        BlockPos pos = info.breakPos;

        if (!level.hasChunkAt(pos) || level.getBlockState(pos).isAir()) {
            cancelBreak(level, frog, info); // already gone (or unloaded): not the frog's failure
            return;
        }
        if (frog.distanceToSqr(Vec3.atCenterOf(pos)) > AiTuning.DIG_HOLD_REACH_SQUARED) {
            failBreak(level, frog, info);
            return;
        }
        if (info.breakProgress % 10 == 0 && isOccupied(level, frog, pos)) {
            failBreak(level, frog, info);
            return;
        }

        info.breakProgress++;

        if (info.breakProgress % 15 == 0) {
            level.playSound(null, frog.getX(), frog.getY(), frog.getZ(),
                    SoundEvents.FROG_AMBIENT, SoundSource.NEUTRAL, 0.8F, 0.5F);
        }

        int stage = Math.min(9, (int) (10.0 * info.breakProgress / info.breakRequired));
        if (stage != info.lastCrackStage) {
            level.destroyBlockProgress(frog.getId(), pos, stage);
            info.lastCrackStage = stage;
        }

        if (info.breakProgress >= info.breakRequired) {
            finishBreak(level, frog, info);
        }
    }

    private static void finishBreak(ServerLevel level, Frog frog, SeekInfo info) {
        BlockPos primary = info.breakPos;
        level.destroyBlockProgress(frog.getId(), primary, -1);

        boolean primaryRemoved = false;
        int removed = 0;
        for (BlockPos cell : info.breakArea) {
            if (!level.hasChunkAt(cell)) {
                continue;
            }
            BlockState state = level.getBlockState(cell);
            if (state.isAir() || state.getDestroySpeed(level, cell) < 0.0F) {
                continue;
            }
            if (!cell.equals(primary) && isOccupied(level, frog, cell)) {
                continue;
            }
            if (level.destroyBlock(cell, true, frog)) { // real removal, with normal drops
                removed++;
                CellClaims.recordBreak(level, cell);    // remember this weak spot
                CellClaims.claim(level, cell);          // briefly keep players from re-filling the cell
                if (cell.equals(primary)) {
                    primaryRemoved = true;
                }
            }
        }

        info.failedAttempts.remove(primary);
        if (!primaryRemoved && FrogTerrain.solid(level, primary)) {
            info.blacklist.add(primary.immutable());
        }
        clearBreak(info);
        info.failStreak = 0;
        info.stuckSamples = 0;
        info.blocksSinceProgress++;

        if (info.blocksSinceProgress >= FrogTuning.MAX_BLOCKS_WITHOUT_PROGRESS) {
            // Too much digging without getting closer: stop digging for a while.
            info.digCooldownUntil = level.getGameTime() + FrogTuning.DIG_COOLDOWN_TICKS;
            info.blocksSinceProgress = 0;
            clearRoute(info);
        }
        TheTwoFrogs.LOGGER.info("A frog broke {} block(s) at {}.", removed, primary.toShortString());
    }

    private static void failBreak(ServerLevel level, Frog frog, SeekInfo info) {
        BlockPos pos = info.breakPos.immutable();
        level.destroyBlockProgress(frog.getId(), pos, -1);
        int fails = info.failedAttempts.merge(pos, 1, Integer::sum);
        if (fails >= FrogTuning.MAX_FAILED_ATTEMPTS_PER_BLOCK) {
            info.blacklist.add(pos);
        }
        clearBreak(info);
        routeFailed(info, pos, level.getGameTime());
    }

    private static void cancelBreak(ServerLevel level, Frog frog, SeekInfo info) {
        if (info.breakPos != null) {
            level.destroyBlockProgress(frog.getId(), info.breakPos, -1);
        }
        clearBreak(info);
        clearRoute(info);
    }

    private static void clearBreak(SeekInfo info) {
        info.breakPos = null;
        info.breakArea = null;
        info.breakProgress = 0;
        info.breakRequired = 0;
        info.lastCrackStage = -1;
    }

    private static boolean isOccupied(ServerLevel level, Frog frog, BlockPos pos) {
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos))) {
            if (entity != frog) {
                return true;
            }
        }
        return false;
    }
}