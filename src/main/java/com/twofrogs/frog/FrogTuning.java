package com.twofrogs.frog;

/** All balance numbers in one place. Change these to tune the feel of the frogs. */
public final class FrogTuning {

    private FrogTuning() {
    }

    // ---- Agitation ----
    public static final double MAX_AGITATION = 100.0;

    /** Agitation gained per second while a frog is not dormant. (0.02 = about 1.2 per minute) */
    public static final double BASE_RATE_PER_SECOND = 0.02;

    /** Extra agitation per second at maximum distance from the partner. */
    public static final double DISTANCE_RATE_PER_SECOND = 0.03;

    /** Horizontal distance (blocks) at which the distance bonus is at its maximum. */
    public static final double DISTANCE_FOR_MAX_BLOCKS = 10000.0;

    /** One-time agitation bumps. */
    public static final double ESCAPE_BONUS = 2.0;
    public static final double RECAPTURE_BONUS = 2.0;

    // ---- Calming the frogs ----
    /** Agitation removed by feeding a captured or escaped special frog one slimeball. */
    public static final double SOOTHE_AMOUNT = 15.0;

    /** Minimum ticks between two slimeballs for the same frog. */
    public static final int SOOTHE_COOLDOWN_TICKS = 60;

    /** Agitation lost per second while the frog is back inside its habitat. (0.15 = about 9 per minute) */
    public static final double HOME_CALM_RATE_PER_SECOND = 0.15;

    // ---- Escaped behavior (values at agitation 0, and at agitation 100) ----
    public static final double SPEED_BONUS_AT_MIN = 1.0;   // +100% movement speed
    public static final double SPEED_BONUS_AT_MAX = 2.5;   // +250% movement speed

    public static final double HOP_CHANCE_PER_SECOND_AT_MIN = 0.15;
    public static final double HOP_CHANCE_PER_SECOND_AT_MAX = 0.60;

    public static final double PAUSE_CHANCE_PER_SECOND_AT_MIN = 0.03;
    public static final double PAUSE_CHANCE_PER_SECOND_AT_MAX = 0.10;

    public static final int PAUSE_MIN_TICKS = 6;
    public static final int PAUSE_MAX_TICKS = 24;

    public static final double HOP_HORIZONTAL_AT_MIN = 0.25;
    public static final double HOP_HORIZONTAL_AT_MAX = 0.50;
    public static final double HOP_VERTICAL_AT_MIN = 0.42;
    public static final double HOP_VERTICAL_AT_MAX = 0.62;

    /** When the frog knows where its partner is, idle hops lean toward it. Spread in radians (+/-). */
    public static final double HOP_HEADING_SPREAD_AT_MIN = 1.2;
    public static final double HOP_HEADING_SPREAD_AT_MAX = 0.4;

    /** Chance that a hop goes in a completely random direction anyway (keeps it unpredictable). */
    public static final double HOP_WILD_CHANCE = 0.08;

    // ---- Pursuit hops (used while it knows where it is going) ----
    /** Ticks between pursuit hops, at agitation 0 and at agitation 100 (plus a little random jitter). */
    public static final int PURSUIT_HOP_COOLDOWN_AT_MIN = 12;
    public static final int PURSUIT_HOP_COOLDOWN_AT_MAX = 6;
    public static final int PURSUIT_HOP_COOLDOWN_JITTER = 2;
    public static final double PURSUIT_HOP_HORIZONTAL_AT_MIN = 0.60;
    public static final double PURSUIT_HOP_HORIZONTAL_AT_MAX = 0.85;
    public static final double PURSUIT_HOP_VERTICAL_AT_MIN = 0.42;
    public static final double PURSUIT_HOP_VERTICAL_AT_MAX = 0.50;
    public static final double PURSUIT_STEP_UP_BOOST = 0.20;
    public static final double PURSUIT_SPREAD = 0.35;

    /** How long each refresh of the Glowing effect lasts (ticks). It is refreshed every second. */
    public static final int GLOW_DURATION_TICKS = 60;

    // ---- Leash escape (chance per SECOND while leashed) ----
    // The base chance follows agitation squared, so low agitation stays genuinely safe.
    public static final double LEASH_ESCAPE_CHANCE_AT_MIN = 0.0005;
    public static final double LEASH_ESCAPE_CHANCE_AT_MAX = 0.035;

    /** Chance is multiplied by up to (1 + this) after LEASH_TIME_CAP_SECONDS of being leashed. */
    public static final double LEASH_TIME_FACTOR_MAX = 1.0;
    public static final int LEASH_TIME_CAP_SECONDS = 300;

    /** Chance is multiplied by up to (1 + this) when the leash is fully stretched. */
    public static final double LEASH_TENSION_FACTOR_MAX = 2.0;

    /** Multiplier while the leash holder is moving around. */
    public static final double LEASH_MOVING_FACTOR = 1.5;

    /** Multiplier when the partner frog is close by (horizontal blocks, same dimension). */
    public static final double PARTNER_NEAR_BLOCKS = 64.0;
    public static final double PARTNER_NEAR_FACTOR = 2.0;
    public static final double PARTNER_MID_BLOCKS = 256.0;
    public static final double PARTNER_MID_FACTOR = 1.5;

    /** Each previous escape adds this much to the multiplier, up to LEASH_HISTORY_MAX_ESCAPES. */
    public static final double LEASH_HISTORY_FACTOR_PER_ESCAPE = 0.1;
    public static final int LEASH_HISTORY_MAX_ESCAPES = 10;

    /** The final chance per second can never exceed this. */
    public static final double LEASH_ESCAPE_CHANCE_CAP = 0.6;

    // ---- Vehicle struggle (boats, rafts, chest boats, minecarts) ----
    /** Chance per second that the frog struggles. Follows agitation squared. */
    public static final double STRUGGLE_CHANCE_AT_MIN = 0.04;
    public static final double STRUGGLE_CHANCE_AT_MAX = 0.5;

    /** Strain added to the vehicle by each struggle (linear in agitation). */
    public static final double STRAIN_PER_STRUGGLE_AT_MIN = 1.0;
    public static final double STRAIN_PER_STRUGGLE_AT_MAX = 3.0;

    /** When accumulated strain reaches this, the vehicle is destroyed and the frog is freed. */
    public static final double STRAIN_LIMIT_BOAT = 12.0;
    public static final double STRAIN_LIMIT_MINECART = 10.0;
    public static final double STRAIN_LIMIT_OTHER = 6.0;

    /** Small vanilla "hit" for the visible wobble on each struggle. */
    public static final float STRUGGLE_WOBBLE_DAMAGE = 0.5F;

    // ---- Seeking: how often it thinks ----
    public static final int DECISION_INTERVAL_TICKS = 10;

    // ---- Seeking: scent ----
    /** Beyond this distance the frog smells nothing and just wanders. */
    public static final double SCENT_RANGE_BLOCKS = 6800.0;

    /** Within this distance it knows exactly where its partner is. */
    public static final double SCENT_EXACT_BLOCKS = 40.0;

    /** Beyond the exact range, its idea of the partner's position is off by up to this fraction of the distance... */
    public static final double SCENT_NOISE_FRACTION = 0.08;

    /** ...but never by more than this many blocks. */
    public static final double SCENT_NOISE_MAX_BLOCKS = 15.0;

    /** How often (ticks) it re-samples its scent estimate (plus up to 3 seconds of jitter). */
    public static final int SCENT_REFRESH_TICKS = 160;

    /** When it smells nothing, it keeps one random heading for this long (ticks). */
    public static final int WANDER_HEADING_TICKS = 600;

    // ---- Seeking: fan of routes ----
    public static final double[] FAN_ANGLES_DEGREES = {0, 20, -20, 40, -40, 65, -65, 95, -95, 130, -130, 180};
    public static final double[] FAN_DISTANCES = {12.0, 8.0, 5.0};

    /** Small preference for routes that point straight at the target. */
    public static final double FAN_ANGLE_PENALTY = 0.03;

    /** How much farther from the target a route may end up, per second without progress (up to the max). */
    public static final double FAN_ALLOWANCE_PER_STUCK_SECOND = 0.8;
    public static final double FAN_ALLOWANCE_MAX = 12.0;

    /** How many candidate routes get a full pathfinding check each time it thinks. */
    public static final int FAN_MAX_PATH_TESTS = 8;

    // ---- Seeking: memory of places it has been ----
    public static final int VISIT_CELL_SIZE = 6;
    public static final int VISIT_MEMORY_TICKS = 2400;
    public static final double VISIT_PENALTY_BLOCKS = 8.0;
    public static final int VISIT_MAX_ENTRIES = 600;

    /** The frog must get at least this much closer to count as progress. */
    public static final double NO_PROGRESS_MARGIN_BLOCKS = 0.5;

    // ---- Seeking: digging routes ----
    /** No walkable way forward for this long (ticks) before it plans a dig route. */
    public static final int DIG_DELAY_TICKS = 30;

    /** Even if it can walk somewhere, this many seconds without progress also triggers a dig route. */
    public static final int STUCK_PLAN_SECONDS = 8;

    public static final int PLAN_MAX_NODES = 6000;
    public static final int PLAN_MAX_RADIUS = 40;
    public static final int PLAN_MAX_STEPS = 12;
    public static final int PLAN_MAX_FALL = 3;

    /** How strongly dig time counts against a route compared with just walking. */
    public static final double PLAN_DIG_COST_PER_SECOND = 1.0;

    /** A plan step that takes longer than this (ticks) is abandoned. */
    public static final int PLAN_STEP_TIMEOUT_TICKS = 120;

    /** After this many failed steps in a row, it stops digging for a while and explores instead. */
    public static final int PLAN_FAILURES_BEFORE_COOLDOWN = 3;
    public static final int DIG_COOLDOWN_TICKS = 600;

    /** A block it fails to break this many times is skipped. */
    public static final int MAX_FAILED_ATTEMPTS_PER_BLOCK = 3;

    /** After breaking this many times without getting closer, it stops digging for a while. */
    public static final int MAX_BLOCKS_WITHOUT_PROGRESS = 20;

    // ---- Block breaking ----
    // Seconds to break = block hardness x factor, then divided by (1 + agitation / AGITATION_FOR_DOUBLE_BREAK_SPEED)
    public static final double BREAK_FACTOR_SHOVEL = 1.5;    // dirt, sand, gravel: very easy
    public static final double BREAK_FACTOR_AXE = 2.5;       // wood: moderate
    public static final double BREAK_FACTOR_PICKAXE = 6.0;   // stone, deepslate, obsidian: hard
    public static final double BREAK_FACTOR_OTHER = 1.5;
    public static final double AGITATION_FOR_DOUBLE_BREAK_SPEED = 40.0;
    public static final double MIN_BREAK_SECONDS = 0.5;

    /** 1 = break just the target block. 2 = clear a 2x2 opening in the same time as one block. */
    public static final int BREAK_AREA_SIZE = 2;

    /** Max squared distance from the frog to a block it is breaking. */
    public static final double BREAK_REACH_SQUARED = 6.25;

    // ---- Re-placed blocks ----
    /** Each time a frog has already broken a spot, breaking it again takes this fraction of the time. */
    public static final double REBREAK_TIME_FACTOR = 0.4;
    public static final double REBREAK_MIN_SECONDS = 0.2;

    /** For this many ticks after a frog breaks a block, players cannot place a block in that exact cell. */
    public static final int CLAIM_TICKS = 60;


    // ---- Rabbit-style fleeing (escaped frogs only) ----
    /** Players closer than this (and visible) alarm the frog. Sneaking and sprinting scale it. */
    public static final double ALERT_RADIUS = 14.0;
    public static final double ALERT_SNEAK_FACTOR = 0.5;
    public static final double ALERT_SPRINT_FACTOR = 1.3;

    /** The higher its agitation, the jumpier it is (up to +50% alarm radius). */
    public static final double ALERT_AGITATION_BONUS = 0.5;

    /** Players this close alarm it even without line of sight (it hears them). */
    public static final double ALERT_HEARING_BLOCKS = 4.0;

    /** How long (ticks) a threat must be noticed before it bolts. Gives a fast player a window. */
    public static final int REACTION_TICKS = 6;

    /** It checks for threats every this many ticks. */
    public static final int SCAN_INTERVAL_TICKS = 3;

    /** After the last threat disappears, it keeps running this long (ticks). */
    public static final int FLEE_LINGER_TICKS = 30;

    /** Under this distance, it mostly ignores its goal and just runs, and may dodge. */
    public static final double FLEE_CLOSE_BLOCKS = 5.0;

    /** Flee hop strength at agitation 0 and agitation 100. Roughly 8 to 10 blocks per second. */
    public static final double FLEE_HOP_HORIZONTAL_AT_MIN = 0.90;
    public static final double FLEE_HOP_HORIZONTAL_AT_MAX = 1.10;
    public static final double FLEE_HOP_VERTICAL_AT_MIN = 0.45;
    public static final double FLEE_HOP_VERTICAL_AT_MAX = 0.52;
    public static final int FLEE_HOP_COOLDOWN_TICKS = 1;

    /** Zigzag: each hop veers by this many degrees, alternating left and right. */
    public static final double ZIGZAG_DEGREES_AT_MIN = 25.0;
    public static final double ZIGZAG_DEGREES_AT_MAX = 45.0;

    /** Chance per hop to juke sharply sideways when a player is very close. */
    public static final double DODGE_CHANCE = 0.35;

    /** When every direction is blocked, it freezes this long (ticks). */
    public static final int CORNERED_FREEZE_TICKS = 8;

    /** Stamina in ticks of sprinting, at agitation 0 and agitation 100. */
    public static final double STAMINA_AT_MIN = 140.0;
    public static final double STAMINA_AT_MAX = 300.0;
    public static final double STAMINA_REGEN_PER_TICK = 0.6;

    /** When stamina runs out, it is tired for this long (ticks), moving at a fraction of speed. */
    public static final int TIRED_TICKS = 120;
    public static final double TIRED_SPEED_FACTOR = 0.45;
    public static final int TIRED_HOP_COOLDOWN_TICKS = 14;

    /** After being fed a slimeball, the frog trusts everyone for this long (ticks). */
    public static final int TRUST_TICKS = 200;


    // ---- Rage (at full agitation) ----
    public static final double RAGE_ENTER_AGITATION = 100.0;
    public static final double RAGE_EXIT_AGITATION = 90.0;
    public static final double RAGE_CHASE_RANGE = 20.0;

    /** An enraged frog notices players this close even without line of sight. */
    public static final double RAGE_SIGHT_FREE_BLOCKS = 8.0;

    public static final double RAGE_ATTACK_REACH = 2.0;
    public static final float RAGE_DAMAGE = 8.0F; // 4 hearts
    public static final int RAGE_WINDUP_TICKS = 8;
    public static final int RAGE_COOLDOWN_TICKS = 40;
    public static final double RAGE_KNOCKBACK = 0.6;
    public static final double RAGE_HOP_HORIZONTAL = 0.65;
    public static final double RAGE_HOP_VERTICAL = 0.45;
    public static final int RAGE_HOP_COOLDOWN_TICKS = 3;

    /** It hunts for this long (ticks), then is out of breath for the rest time (it still bites in reach). */
    public static final int RAGE_BURST_TICKS = 240;
    public static final int RAGE_REST_TICKS = 100;

    // ---- Agitation hints ----
    public static final double HINT_TIER_1 = 25.0;
    public static final double HINT_TIER_2 = 50.0;
    public static final double HINT_TIER_3 = 75.0;
    public static final double HINT_TIER_4 = 90.0;
    public static final double HINT_RADIUS_BLOCKS = 32.0;
    public static final int HINT_AMBIENT_MIN_TICKS = 300;
    public static final int HINT_AMBIENT_EXTRA_TICKS = 300;


    // ---- Direct partner seeking (movement controller) ----
    /** How far ahead (blocks) it checks the straight line to its partner for walls. */
    public static final int DIRECT_LOOKAHEAD_BLOCKS = 10;

    /** If it makes no progress this many seconds while heading straight, it plans a route instead. */
    public static final int DIRECT_STUCK_SECONDS = 5;

    /** While evading a player it never turns more than this far from the partner's direction. */
    public static final double EVADE_MAX_DEGREES = 60.0;
    public static final double EVADE_SIDESTEP = 0.25;
    public static final double EVADE_BLOCKED_SIDESTEP = 0.6;

    /** Hops along a planned route are scaled to the distance so it lands in the right cell. */
    public static final double PRECISE_HOP_FACTOR = 0.14;
    public static final double PRECISE_HOP_MIN = 0.10;
    public static final int PRECISE_HOP_COOLDOWN_TICKS = 3;

    /** Vertical push for hopping up onto a block. */
    public static final double STEP_UP_VERTICAL = 0.52;
}