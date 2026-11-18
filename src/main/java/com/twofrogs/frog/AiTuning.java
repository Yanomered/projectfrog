package com.twofrogs.frog;

/** Numbers for the frog movement and navigation AI. Change these to tune it. */
public final class AiTuning {

    private AiTuning() {
    }

    // ---- Route planning ----
    public static final int ROUTE_MAX_NODES = 6000;
    public static final int ROUTE_MAX_RADIUS = 40;
    public static final int ROUTE_MAX_STEPS = 30;
    public static final int ROUTE_MAX_FALL = 3;
    public static final double DIG_BASE_COST = 2.0;
    public static final double DIG_COST_PER_SECOND = 1.5;
    public static final double MIN_ROUTE_GAIN_BLOCKS = 1.5;

    // ---- Digging speed ----
    /** If true, every breakable block takes as long as dirt. Set to false for the old per-material table. */
    public static final boolean UNIFORM_BREAK_TIME = true;
    public static final double DIRT_BREAK_SECONDS = 0.75;

    // ---- Thinking ----
    public static final int DECISION_INTERVAL_TICKS = 10;
    public static final double SENSE_RANGE_BLOCKS = 6000.0;
    public static final int PARTIAL_REPLAN_TICKS = 60;
    public static final int GOAL_MOVED_REPLAN_CELLS = 3;
    public static final double OFF_ROUTE_BLOCKS = 4.0;
    public static final double FINAL_APPROACH_BLOCKS = 3.0;

    /** How many route cells ahead the frog may aim at when cruising. */
    public static final int SMOOTH_LOOKAHEAD_CELLS = 8;

    // ---- Following the route ----
    /** A route cell counts as reached when the frog passes within this horizontal distance of it... */
    public static final double ROUTE_REACH_RADIUS = 1.0;
    /** ...and is no lower than this below it, or higher than this above it (hopping over a cell counts). */
    public static final double ROUTE_REACH_Y_BELOW = 0.6;
    public static final double ROUTE_REACH_Y_ABOVE = 3.5;
    /** If a hop carries the frog past several cells, it skips ahead (never past a block it still has to dig). */
    public static final int ROUTE_SKIP_AHEAD_CELLS = 6;

    // ---- Stuck recovery ----
    public static final int STUCK_SAMPLE_TICKS = 20;
    public static final double STUCK_MOVE_BLOCKS = 0.3;
    public static final int STUCK_SAMPLES_BEFORE_FAIL = 3;
    public static final int AVOID_TICKS = 300;
    public static final int FAIL_STREAK_BEFORE_EXPLORE = 3;
    public static final int NO_ROUTE_EXPLORE_TICKS = 60;

    // ---- Digging reach ----
    public static final double DIG_START_REACH_SQUARED = 7.5;
    public static final double DIG_HOLD_REACH_SQUARED = 9.0;

    // ---- Roaming (no partner to go to) ----
    public static final int ROAM_MIN_BLOCKS = 6;
    public static final int ROAM_EXTRA_BLOCKS = 12;
    public static final int ROAM_TRIES = 4;
    public static final int ROAM_IDLE_MIN_TICKS = 20;
    public static final int ROAM_IDLE_EXTRA_TICKS = 80;

    // ---- Hopping ----
    /** Horizontal impulse per block of distance. A hop travels about 5.4 times its horizontal impulse. */
    public static final double HOP_DISTANCE_FACTOR = 0.185;
    public static final double HOP_MIN_HORIZONTAL = 0.10;

    /** Vertical impulse for hopping up onto a block (strong enough to clear its edge quickly). */
    public static final double STEP_UP_VERTICAL = 0.62;

    /** Cooldown between hops along a precise route (steps up, drops, digging, narrow spots). */
    public static final int PRECISE_COOLDOWN_TICKS = 3;

    /** Tiny sideways wobble on calm hops (radians), so it does not look robotic. */
    public static final double CALM_JITTER_RADIANS = 0.05;

    // ---- Evading players while heading for the partner ----
    public static final double EVADE_MAX_DEGREES = 45.0;
    public static final double EVADE_SIDESTEP = 0.25;
    public static final double EVADE_BLOCKED_SIDESTEP = 0.6;
    public static final double DODGE_DEGREES = 30.0;

    // ---- Unsticking ----
    public static final double WIGGLE_HORIZONTAL = 0.30;
    public static final double WIGGLE_VERTICAL = 0.55;
    public static final int WIGGLE_COOLDOWN_TICKS = 8;

    // ---- Digging straight through whatever is in front ----
    public static final boolean DIG_IN_FRONT = true;
    public static final boolean DIG_HOPPABLE_BLOCKS = false;
    public static final double FRONT_DIG_MAX_VERTICAL_BLOCKS = 2.0;
    public static final double FRONT_DIG_REACH = 1.7;
    public static final double FRONT_IGNORE_HEIGHT = 0.6;
}