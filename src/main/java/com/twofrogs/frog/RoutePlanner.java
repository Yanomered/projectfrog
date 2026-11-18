package com.twofrogs.frog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Plans a route for a frog over the real blocks (A*). Moves understood: walk, drop up to a few blocks,
 * swim, hop up onto a block, and (when allowed) dig forward, dig down, or clear the block above a step to
 * build a staircase. Digging is priced by how long the block takes to break, so walking is preferred
 * unless digging is genuinely cheaper.
 * Cells are standing cells: the air cell a frog's feet are in.
 */
public final class RoutePlanner {

    public enum StepKind {
        WALK, DIG
    }

    /** One step of a route. 'dig' is the block to break first (may be null). 'dir' is the dig direction. */
    public record Step(StepKind kind, BlockPos dest, BlockPos dig, Direction dir) {
    }

    /** 'reachesGoal' is true when the route ends next to the goal (an empty route with true means "already there"). */
    public record Route(List<Step> steps, boolean reachesGoal) {
    }

    private static final Route NONE = new Route(List.of(), false);

    private record Node(BlockPos pos, double g, double h, Node parent, Step step) {
        double f() {
            return g + h;
        }
    }

    private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        boolean contains(BlockPos p) {
            return p.getX() >= minX && p.getX() <= maxX
                    && p.getY() >= minY && p.getY() <= maxY
                    && p.getZ() >= minZ && p.getZ() <= maxZ;
        }
    }

    private RoutePlanner() {
    }

    // ---------- Entry point ----------

    public static Route plan(ServerLevel level, BlockPos start, BlockPos goal, boolean allowDig,
                             double agitation, Set<BlockPos> blocked) {
        if (!level.hasChunkAt(start) || !level.hasChunkAt(goal)) {
            return NONE;
        }
        if (isGoal(start, goal)) {
            return new Route(List.of(), true);
        }

        double hx = goal.getX() - start.getX();
        double hz = goal.getZ() - start.getZ();
        int radius = Mth.clamp((int) Math.sqrt(hx * hx + hz * hz) + 10, 16, AiTuning.ROUTE_MAX_RADIUS);
        Bounds bounds = new Bounds(
                start.getX() - radius, start.getX() + radius,
                Math.min(start.getY(), goal.getY()) - 12, Math.max(start.getY(), goal.getY()) + 12,
                start.getZ() - radius, start.getZ() + radius);

        Search search = new Search(new Grid(level, blocked), bounds, goal, allowDig, agitation);
        Node startNode = new Node(start, 0.0, search.heuristic(start), null, null);
        search.open.add(startNode);
        search.bestG.put(start.asLong(), 0.0);

        Node bestNode = startNode;
        boolean reached = false;
        int expanded = 0;
        while (!search.open.isEmpty() && expanded < AiTuning.ROUTE_MAX_NODES) {
            Node current = search.open.poll();
            if (current.g() > search.bestG.getOrDefault(current.pos().asLong(), Double.MAX_VALUE) + 1.0E-9) {
                continue; // outdated entry
            }
            expanded++;

            if (isGoal(current.pos(), goal)) {
                bestNode = current;
                reached = true;
                break;
            }
            if (current.h() < bestNode.h() - 1.0E-9
                    || (Math.abs(current.h() - bestNode.h()) <= 1.0E-9 && current.g() < bestNode.g())) {
                bestNode = current;
            }
            search.expand(current);
        }

        // Did not reach the goal and did not get meaningfully closer: no useful route.
        if (!reached && (bestNode == startNode || bestNode.h() > startNode.h() - AiTuning.MIN_ROUTE_GAIN_BLOCKS)) {
            return NONE;
        }

        List<Step> steps = new ArrayList<>();
        for (Node n = bestNode; n != null && n.step() != null; n = n.parent()) {
            steps.add(n.step());
        }
        Collections.reverse(steps);

        boolean truncated = false;
        if (steps.size() > AiTuning.ROUTE_MAX_STEPS) {
            steps = new ArrayList<>(steps.subList(0, AiTuning.ROUTE_MAX_STEPS));
            truncated = true;
        }
        return new Route(steps, reached && !truncated);
    }

    private static boolean isGoal(BlockPos pos, BlockPos goal) {
        return Math.abs(pos.getX() - goal.getX()) <= 1
                && Math.abs(pos.getZ() - goal.getZ()) <= 1
                && Math.abs(pos.getY() - goal.getY()) <= 1;
    }

    // ---------- Break time (shared with the digging code) ----------

    /** Seconds the frog needs to break the block at 'pos'. */
    public static double breakSeconds(ServerLevel level, BlockPos pos, double agitation) {
        double seconds;
        if (AiTuning.UNIFORM_BREAK_TIME) {
            seconds = AiTuning.DIRT_BREAK_SECONDS; // every block breaks as fast as dirt
        } else {
            BlockState state = level.getBlockState(pos);
            seconds = state.getDestroySpeed(level, pos) * breakFactor(state);
        }
        seconds /= 1.0 + agitation / FrogTuning.AGITATION_FOR_DOUBLE_BREAK_SPEED;

        int previousBreaks = CellClaims.breakCount(level, pos);
        if (previousBreaks > 0) {
            // The frog has broken this spot before and knows the weak point.
            seconds *= Math.pow(FrogTuning.REBREAK_TIME_FACTOR, previousBreaks);
            return Math.max(seconds, FrogTuning.REBREAK_MIN_SECONDS);
        }
        return Math.max(seconds, FrogTuning.MIN_BREAK_SECONDS);
    }

    private static double breakFactor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return FrogTuning.BREAK_FACTOR_PICKAXE;
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return FrogTuning.BREAK_FACTOR_AXE;
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return FrogTuning.BREAK_FACTOR_SHOVEL;
        }
        return FrogTuning.BREAK_FACTOR_OTHER;
    }

    // ---------- A cached, read-only view of the world ----------

    private static final class Grid {
        static final byte OPEN = 0;
        static final byte SOFT = 1;   // solid and breakable
        static final byte HARD = 2;   // unbreakable, blocked, unloaded or lava

        final ServerLevel level;
        private final Set<BlockPos> blocked;
        private final Map<Long, Byte> cache = new HashMap<>();
        private final Map<Long, Boolean> lowCache = new HashMap<>();

        Grid(ServerLevel level, Set<BlockPos> blocked) {
            this.level = level;
            this.blocked = blocked;
        }

        byte cls(BlockPos pos) {
            long key = pos.asLong();
            Byte cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            byte result = compute(pos);
            cache.put(key, result);
            return result;
        }

        private byte compute(BlockPos pos) {
            if (!level.hasChunkAt(pos) || blocked.contains(pos)) {
                return HARD;
            }
            if (level.getFluidState(pos).is(FluidTags.LAVA)) {
                return HARD;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getCollisionShape(level, pos).isEmpty()) {
                return OPEN;
            }
            if (state.getDestroySpeed(level, pos) < 0.0F) {
                return HARD;
            }
            return SOFT;
        }

        /** True if a frog can hop up onto this block (not a fence, wall or other tall block). */
        boolean low(BlockPos pos) {
            long key = pos.asLong();
            Boolean cached = lowCache.get(key);
            if (cached != null) {
                return cached;
            }
            boolean result = FrogTerrain.climbable(level, pos);
            lowCache.put(key, result);
            return result;
        }

        boolean water(BlockPos pos) {
            return level.hasChunkAt(pos) && !level.getFluidState(pos).isEmpty();
        }
    }

    // ---------- The search ----------

    private static final class Search {
        final Grid grid;
        final Bounds bounds;
        final BlockPos goal;
        final boolean allowDig;
        final double agitation;
        final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        final Map<Long, Double> bestG = new HashMap<>();

        Search(Grid grid, Bounds bounds, BlockPos goal, boolean allowDig, double agitation) {
            this.grid = grid;
            this.bounds = bounds;
            this.goal = goal;
            this.allowDig = allowDig;
            this.agitation = agitation;
        }

        double heuristic(BlockPos p) {
            double dx = p.getX() - goal.getX();
            double dy = p.getY() - goal.getY();
            double dz = p.getZ() - goal.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private double digCost(BlockPos pos) {
            return AiTuning.DIG_BASE_COST
                    + breakSeconds(grid.level, pos, agitation) * AiTuning.DIG_COST_PER_SECOND;
        }

        /** Where the frog ends up after entering 'start' and falling (up to ROUTE_MAX_FALL blocks), or null. */
        private BlockPos landing(BlockPos start) {
            BlockPos p = start;
            int fall = 0;
            while (grid.cls(p.below()) == Grid.OPEN && !grid.water(p)) {
                if (fall >= AiTuning.ROUTE_MAX_FALL) {
                    return null;
                }
                p = p.below();
                fall++;
            }
            return p;
        }

        private void consider(Node from, BlockPos pos, double cost, Step step) {
            if (!bounds.contains(pos)) {
                return;
            }
            double g = from.g() + cost;
            Double old = bestG.get(pos.asLong());
            if (old != null && old <= g) {
                return;
            }
            bestG.put(pos.asLong(), g);
            open.add(new Node(pos, g, heuristic(pos), from, step));
        }

        void expand(Node current) {
            BlockPos c = current.pos();
            boolean headroom = grid.cls(c.above()) == Grid.OPEN;

            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                byte cn = grid.cls(n);

                if (cn == Grid.OPEN) {
                    // Walk, possibly dropping a little.
                    BlockPos land = landing(n);
                    if (land != null) {
                        double cost = 1.0 + 0.3 * (n.getY() - land.getY()) + (grid.water(land) ? 1.0 : 0.0);
                        consider(current, land, cost, new Step(StepKind.WALK, land, null, d));
                    }
                    continue;
                }

                // Something solid in the way: hop up onto it (if it is a normal block), or dig.
                if (headroom && grid.low(n)) {
                    BlockPos up = n.above();
                    byte cu = grid.cls(up);
                    if (cu == Grid.OPEN) {
                        consider(current, up, 2.0, new Step(StepKind.WALK, up, null, d));
                    } else if (cu == Grid.SOFT && allowDig) {
                        // Clear the space above it, then hop up (this builds a staircase).
                        consider(current, up, 2.0 + digCost(up), new Step(StepKind.DIG, up, up, d));
                    }
                }
                if (allowDig && cn == Grid.SOFT) {
                    BlockPos land = landing(n);
                    if (land != null) {
                        double cost = 1.0 + digCost(n) + 0.3 * (n.getY() - land.getY());
                        consider(current, land, cost, new Step(StepKind.DIG, land, n, d));
                    }
                }
            }

            // Dig down.
            BlockPos below = c.below();
            if (allowDig && grid.cls(below) == Grid.SOFT) {
                BlockPos land = landing(below);
                if (land != null) {
                    double cost = 1.0 + digCost(below) + 0.3 * (below.getY() - land.getY());
                    consider(current, land, cost, new Step(StepKind.DIG, land, below, null));
                }
            }
        }
    }
}