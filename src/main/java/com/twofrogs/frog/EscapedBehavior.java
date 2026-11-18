package com.twofrogs.frog;

import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hops an escaped special frog toward the waypoint PartnerSeeker chose (the vanilla frog brain is skipped
 * while a frog is free; see EscapeControl).
 *  - Cruising (open ground, even over bumps): full-strength hops toward a point several cells ahead.
 *  - Precise (climbs, drops, digging, the last cell): hops scaled to land where they should.
 *  - With a partner to reach, it ALWAYS heads along its route. Players only make it dodge and zigzag within
 *    45 degrees of the route, and sprint faster. It never turns back.
 *  - With no partner to reach, it roams (PartnerSeeker picks walks and rests) or bolts away from players.
 *  - Sprinting costs stamina. When it runs out the frog is tired and slow: that is the time to catch it.
 *  - Slimeballs make no difference to it.
 * All timers are temporary (not saved).
 */
public class EscapedBehavior {

    private static final Map<UUID, Integer> HOP_COOLDOWN = new HashMap<>();
    private static final Map<UUID, Mood> MOODS = new HashMap<>();

    private static final class Mood {
        double stamina = -1.0;
        int tiredTicks = 0;
        int alertTicks = 0;
        int fleeTicks = 0;
        int freezeTicks = 0;
        int hopCooldown = 0;
        int zigzagSign = 1;

        boolean threatened = false;
        double nearest = Double.MAX_VALUE;
        Vec3 away = Vec3.ZERO;
        Vec3 lastAway = Vec3.ZERO;
    }

    // ---------- Main loop ----------

    /** The frog has just escaped: it is startled and bolts away from the nearest player at once. */
    public static void startle(Frog frog) {
        if (!(frog.level() instanceof ServerLevel level)) {
            return;
        }
        Mood mood = MOODS.computeIfAbsent(frog.getUUID(), k -> new Mood());
        scanThreats(level, frog, mood, 0.5);
        mood.alertTicks = FrogTuning.REACTION_TICKS;
        mood.fleeTicks = FrogTuning.FLEE_LINGER_TICKS * 2;
        mood.hopCooldown = 0;
        HOP_COOLDOWN.remove(frog.getUUID());
    }

    public static void onServerTick(MinecraftServer server) {
        TwoFrogsState state = TwoFrogsState.get(server);
        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                continue;
            }
            if (state.getBehavior(role) != FrogBehavior.ESCAPED) {
                MOODS.remove(uuid.get());
                HOP_COOLDOWN.remove(uuid.get());
                continue;
            }
            Frog frog = SpecialFrogManager.findFrog(server, uuid.get());
            if (frog == null) {
                HOP_COOLDOWN.remove(uuid.get());
                continue;
            }
            tickFrog(frog, state.getAgitation(role));
        }
    }

    private static void tickFrog(Frog frog, double agitation) {
        UUID id = frog.getUUID();
        ServerLevel serverLevel = (ServerLevel) frog.level();
        RandomSource random = frog.getRandom();
        long now = serverLevel.getGameTime();
        double level = Mth.clamp(agitation / FrogTuning.MAX_AGITATION, 0.0, 1.0);
        double maxStamina = Mth.lerp(level, FrogTuning.STAMINA_AT_MIN, FrogTuning.STAMINA_AT_MAX);

        Mood mood = MOODS.computeIfAbsent(id, k -> new Mood());
        if (mood.stamina < 0.0) {
            mood.stamina = maxStamina;
        }

        // Look for threats every few ticks.
        if (now % FrogTuning.SCAN_INTERVAL_TICKS == 0) {
            scanThreats(serverLevel, frog, mood, level);
            if (mood.threatened) {
                mood.alertTicks += FrogTuning.SCAN_INTERVAL_TICKS;
                if (mood.alertTicks >= FrogTuning.REACTION_TICKS) {
                    mood.fleeTicks = FrogTuning.FLEE_LINGER_TICKS;
                }
            } else {
                mood.alertTicks = 0;
            }
        }

        // An enraged frog does not run from players: it hunts them instead.
        if (RageBehavior.isEnraged(id)) {
            mood.fleeTicks = 0;
            mood.alertTicks = 0;
        }
        if (RageBehavior.isChasing(id)) {
            return; // RageBehavior drives the movement while it hunts a player
        }

        boolean alarmed = mood.fleeTicks > 0;
        if (alarmed) {
            mood.fleeTicks--;
        }

        // Stamina: sprinting drains it, resting restores it, running out makes the frog tired.
        if (mood.tiredTicks > 0) {
            mood.tiredTicks--;
            if (mood.tiredTicks == 0) {
                mood.stamina = maxStamina * 0.35;
            }
        } else if (alarmed) {
            mood.stamina -= 1.0;
            if (mood.stamina <= 0.0) {
                mood.tiredTicks = FrogTuning.TIRED_TICKS;
            }
        } else {
            mood.stamina = Math.min(maxStamina, mood.stamina + FrogTuning.STAMINA_REGEN_PER_TICK);
        }
        boolean tired = mood.tiredTicks > 0;

        // While breaking a block the frog stands still and concentrates (even if a player is near).
        if (PartnerSeeker.isBreaking(id)) {
            HOP_COOLDOWN.remove(id);
            if (FrogTerrain.grounded(frog)) {
                frog.setDeltaMovement(0.0, frog.getDeltaMovement().y, 0.0);
            }
            return;
        }

        boolean seeking = PartnerSeeker.isSeeking(id);
        Vec3 waypoint = PartnerSeeker.getWaypoint(id);
        boolean free = PartnerSeeker.isFree(id);
        boolean cruise = PartnerSeeker.isCruise(id);

        // Frightened with no partner to go to: bolt away from players like a rabbit.
        if (alarmed && !seeking) {
            HOP_COOLDOWN.remove(id);
            flee(serverLevel, frog, mood, level, tired, random);
            return;
        }

        // In water the frog paddles toward its goal.
        if (frog.isInWater() || frog.isInLava()) {
            swim(frog, waypoint);
            return;
        }

        boolean grounded = FrogTerrain.grounded(frog);

        // Stuck: a sideways wiggle to get loose.
        if (grounded && PartnerSeeker.consumeWiggle(id)) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            frog.setDeltaMovement(Math.cos(angle) * AiTuning.WIGGLE_HORIZONTAL, AiTuning.WIGGLE_VERTICAL,
                    Math.sin(angle) * AiTuning.WIGGLE_HORIZONTAL);
            HOP_COOLDOWN.put(id, AiTuning.WIGGLE_COOLDOWN_TICKS);
            return;
        }

        // Nowhere to go right now (resting, or waiting for a route): stay put.
        if (waypoint == null) {
            HOP_COOLDOWN.remove(id);
            return;
        }

        int cooldown = HOP_COOLDOWN.getOrDefault(id, 0);
        if (cooldown > 0) {
            HOP_COOLDOWN.put(id, cooldown - 1);
            return;
        }
        if (!grounded) {
            return;
        }

        boolean hopped = seekHop(serverLevel, frog, mood, random, level, waypoint, free, cruise, alarmed, tired);
        int next;
        if (!hopped) {
            next = 3;
        } else if (tired) {
            next = FrogTuning.TIRED_HOP_COOLDOWN_TICKS;
        } else if (!free && !cruise) {
            next = AiTuning.PRECISE_COOLDOWN_TICKS;
        } else if (alarmed) {
            next = FrogTuning.FLEE_HOP_COOLDOWN_TICKS;
        } else {
            next = (int) Mth.lerp(level, FrogTuning.PURSUIT_HOP_COOLDOWN_AT_MIN,
                    FrogTuning.PURSUIT_HOP_COOLDOWN_AT_MAX)
                    + random.nextInt(FrogTuning.PURSUIT_HOP_COOLDOWN_JITTER + 1);
        }
        HOP_COOLDOWN.put(id, next);
    }

    // ---------- Threat detection (slimeballs make no difference) ----------

    private static void scanThreats(ServerLevel level, Frog frog, Mood mood, double agitationLevel) {
        Vec3 away = Vec3.ZERO;
        double nearest = Double.MAX_VALUE;
        boolean any = false;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double distance = player.distanceTo(frog);
            double radius = FrogTuning.ALERT_RADIUS
                    * (player.isShiftKeyDown() ? FrogTuning.ALERT_SNEAK_FACTOR
                    : player.isSprinting() ? FrogTuning.ALERT_SPRINT_FACTOR : 1.0)
                    * (1.0 + FrogTuning.ALERT_AGITATION_BONUS * agitationLevel);
            if (distance > radius) {
                continue;
            }
            if (distance > FrogTuning.ALERT_HEARING_BLOCKS && !frog.hasLineOfSight(player)) {
                continue;
            }

            Vec3 diff = new Vec3(frog.getX() - player.getX(), 0.0, frog.getZ() - player.getZ());
            if (diff.lengthSqr() < 1.0E-4) {
                double angle = frog.getRandom().nextDouble() * Math.PI * 2.0;
                diff = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
            }
            away = away.add(diff.normalize().scale(1.0 / Math.max(distance, 1.0)));
            nearest = Math.min(nearest, distance);
            any = true;
        }

        mood.threatened = any;
        mood.nearest = nearest;
        if (any) {
            mood.away = away.normalize();
            mood.lastAway = mood.away;
        }
    }

    // ---------- Hopping toward the waypoint ----------

    /** One hop toward the waypoint. Returns false if it could not hop (already at the waypoint). */
    private static boolean seekHop(ServerLevel level, Frog frog, Mood mood, RandomSource random,
                                   double agitationLevel, Vec3 waypoint, boolean free, boolean cruise,
                                   boolean alarmed, boolean tired) {
        double dx = waypoint.x - frog.getX();
        double dz = waypoint.z - frog.getZ();
        double dy = waypoint.y - frog.getY();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.05) {
            return false;
        }

        double baseAngle = Math.atan2(dz, dx);
        double angle = baseAngle;

        // On open ground the frog may deviate a little. Elsewhere it follows the route exactly.
        if (free) {
            if (alarmed) {
                Vec3 away = mood.threatened ? mood.away : mood.lastAway;
                Vec3 direction = new Vec3(dx / distance, 0.0, dz / distance);
                double dot = direction.x * away.x + direction.z * away.z;
                Vec3 perpendicular = new Vec3(-direction.z, 0.0, direction.x);
                double sign = (perpendicular.x * away.x + perpendicular.z * away.z) >= 0.0 ? 1.0 : -1.0;
                double sidestep = dot < -0.2 ? AiTuning.EVADE_BLOCKED_SIDESTEP : AiTuning.EVADE_SIDESTEP;
                Vec3 desired = direction.add(perpendicular.scale(sign * sidestep));
                angle = Math.atan2(desired.z, desired.x);

                mood.zigzagSign = -mood.zigzagSign;
                angle += Math.toRadians(Mth.lerp(agitationLevel, FrogTuning.ZIGZAG_DEGREES_AT_MIN,
                        FrogTuning.ZIGZAG_DEGREES_AT_MAX)) * 0.5 * mood.zigzagSign * (0.6 + 0.4 * random.nextDouble());
                if (mood.nearest < FrogTuning.FLEE_CLOSE_BLOCKS && random.nextDouble() < FrogTuning.DODGE_CHANCE) {
                    angle += (random.nextBoolean() ? 1.0 : -1.0) * Math.toRadians(AiTuning.DODGE_DEGREES);
                }

                // Never turn away from the route by more than the limit, and stay out of walls.
                angle = clearAngle(level, frog, baseAngle, angle, Math.toRadians(AiTuning.EVADE_MAX_DEGREES));
            } else {
                angle += (random.nextDouble() * 2.0 - 1.0) * AiTuning.CALM_JITTER_RADIANS;
            }
        }

        double base = alarmed
                ? Mth.lerp(agitationLevel, FrogTuning.FLEE_HOP_HORIZONTAL_AT_MIN, FrogTuning.FLEE_HOP_HORIZONTAL_AT_MAX)
                : Mth.lerp(agitationLevel, FrogTuning.PURSUIT_HOP_HORIZONTAL_AT_MIN, FrogTuning.PURSUIT_HOP_HORIZONTAL_AT_MAX);
        // Cruising: full-strength hops. Otherwise the hop is scaled to land where it should.
        double horizontal = cruise
                ? base
                : Mth.clamp(distance * AiTuning.HOP_DISTANCE_FACTOR, AiTuning.HOP_MIN_HORIZONTAL, base);
        if (tired && (free || cruise)) {
            horizontal *= FrogTuning.TIRED_SPEED_FACTOR;
        }

        double vertical = alarmed
                ? Mth.lerp(agitationLevel, FrogTuning.FLEE_HOP_VERTICAL_AT_MIN, FrogTuning.FLEE_HOP_VERTICAL_AT_MAX)
                : Mth.lerp(agitationLevel, FrogTuning.PURSUIT_HOP_VERTICAL_AT_MIN, FrogTuning.PURSUIT_HOP_VERTICAL_AT_MAX);
        if (dy > 0.6) {
            vertical = Math.max(vertical, AiTuning.STEP_UP_VERTICAL); // hopping up onto a block
        }

        frog.setDeltaMovement(Math.cos(angle) * horizontal, vertical, Math.sin(angle) * horizontal);
        return true;
    }

    /** The preferred angle if it is open, otherwise the nearest open angle within 'limit' of the base angle. */
    private static double clearAngle(ServerLevel level, Frog frog, double baseAngle, double preferred, double limit) {
        double[] offsetsDegrees = {0, 25, -25, 50, -50};
        for (double offset : offsetsDegrees) {
            double candidate = baseAngle + Mth.clamp(wrap(preferred + Math.toRadians(offset) - baseAngle), -limit, limit);
            if (isClear(level, frog, candidate)) {
                return candidate;
            }
        }
        return baseAngle + Mth.clamp(wrap(preferred - baseAngle), -limit, limit);
    }

    private static double wrap(double radians) {
        while (radians > Math.PI) {
            radians -= Math.PI * 2.0;
        }
        while (radians < -Math.PI) {
            radians += Math.PI * 2.0;
        }
        return radians;
    }

    private static void swim(Frog frog, Vec3 waypoint) {
        Vec3 v = frog.getDeltaMovement();
        double vy = frog.isUnderWater() ? Math.max(v.y, 0.08) : Math.min(v.y, 0.0);

        double ux = 0.0;
        double uz = 0.0;
        if (waypoint != null) {
            double dx = waypoint.x - frog.getX();
            double dz = waypoint.z - frog.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 0.05) {
                ux = dx / length;
                uz = dz / length;
            }
            if (waypoint.y - frog.getY() > 0.5 && length < 1.8) {
                vy = 0.42; // hop out of the water onto the bank
            }
        }
        frog.setDeltaMovement(ux * 0.16, vy, uz * 0.16);
    }

    // ---------- Pure fleeing (only when the frog has no partner to go to) ----------

    private static void flee(ServerLevel level, Frog frog, Mood mood, double agitationLevel,
                             boolean tired, RandomSource random) {
        Vec3 away = mood.threatened ? mood.away : mood.lastAway;
        if (away.lengthSqr() < 1.0E-4) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            away = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
        }

        // In water the frog just paddles away.
        if (frog.isInWater() || frog.isInLava()) {
            Vec3 v = frog.getDeltaMovement();
            frog.setDeltaMovement(away.x * 0.12, v.y, away.z * 0.12);
            return;
        }

        // Cornered: freeze for a moment.
        if (mood.freezeTicks > 0) {
            mood.freezeTicks--;
            if (FrogTerrain.grounded(frog)) {
                frog.setDeltaMovement(0.0, frog.getDeltaMovement().y, 0.0);
            }
            return;
        }

        if (mood.hopCooldown > 0) {
            mood.hopCooldown--;
            return;
        }
        if (!FrogTerrain.grounded(frog)) {
            return;
        }

        double baseAngle = Math.atan2(away.z, away.x);

        mood.zigzagSign = -mood.zigzagSign;
        double zigzag = Math.toRadians(Mth.lerp(agitationLevel, FrogTuning.ZIGZAG_DEGREES_AT_MIN,
                FrogTuning.ZIGZAG_DEGREES_AT_MAX)) * mood.zigzagSign * (0.6 + 0.4 * random.nextDouble());
        double angle = baseAngle + zigzag;
        if (mood.nearest < FrogTuning.FLEE_CLOSE_BLOCKS && random.nextDouble() < FrogTuning.DODGE_CHANCE) {
            angle = baseAngle + (random.nextBoolean() ? 1.0 : -1.0) * Math.toRadians(90.0);
        }

        Double clear = findClearAngle(level, frog, angle);
        if (clear == null) {
            mood.freezeTicks = FrogTuning.CORNERED_FREEZE_TICKS;
            return;
        }

        double horizontal = Mth.lerp(agitationLevel, FrogTuning.FLEE_HOP_HORIZONTAL_AT_MIN,
                FrogTuning.FLEE_HOP_HORIZONTAL_AT_MAX) * (tired ? FrogTuning.TIRED_SPEED_FACTOR : 1.0);
        double vertical = Mth.lerp(agitationLevel, FrogTuning.FLEE_HOP_VERTICAL_AT_MIN,
                FrogTuning.FLEE_HOP_VERTICAL_AT_MAX);
        frog.setDeltaMovement(Math.cos(clear) * horizontal, vertical, Math.sin(clear) * horizontal);
        mood.hopCooldown = tired ? FrogTuning.TIRED_HOP_COOLDOWN_TICKS : FrogTuning.FLEE_HOP_COOLDOWN_TICKS;
    }

    /** The preferred angle if it is open, otherwise the nearest open angle, otherwise null (cornered). */
    private static Double findClearAngle(ServerLevel level, Frog frog, double preferred) {
        double[] offsetsDegrees = {0, 30, -30, 60, -60, 90, -90, 125, -125};
        for (double offset : offsetsDegrees) {
            double angle = preferred + Math.toRadians(offset);
            if (isClear(level, frog, angle)) {
                return angle;
            }
        }
        return null;
    }

    private static boolean isClear(ServerLevel level, Frog frog, double angle) {
        int y = frog.blockPosition().getY();
        for (int step = 1; step <= 3; step++) {
            int x = Mth.floor(frog.getX() + Math.cos(angle) * step);
            int z = Mth.floor(frog.getZ() + Math.sin(angle) * step);
            BlockPos foot = new BlockPos(x, y, z);
            if (!level.hasChunkAt(foot)) {
                return false;
            }
            if (FrogTerrain.solid(level, foot) && FrogTerrain.solid(level, foot.above())) {
                return false; // wall (a single block can be hopped over)
            }
        }
        return true;
    }
}