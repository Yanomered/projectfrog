package com.twofrogs.encounter;

/** All the numbers for the journey encounters. Change these to tune them. */
public final class EncounterTuning {

    private EncounterTuning() {
    }

    // ---- The journey ----
    /** Encounters start this far (blocks) from the habitat, and reach full strength at FULL_JOURNEY_BLOCKS. */
    public static final double MIN_JOURNEY_BLOCKS = 300.0;
    public static final double FULL_JOURNEY_BLOCKS = 3500.0;

    // ---- Pacing ----
    public static final int FIRST_GRACE_SECONDS = 90;
    public static final double INTERVAL_AT_START_SECONDS = 150.0;
    public static final double INTERVAL_AT_END_SECONDS = 60.0;
    public static final int MIN_GAP_TICKS = 45 * 20;
    public static final int RETRY_TICKS = 20 * 20;

    // ---- Group sizes and caps ----
    public static final int MAX_GROUP = 5;
    public static final int PER_PLAYER_CAP = 6;
    public static final int PER_PLAYER_CAP_LATE = 8;
    public static final double LATE_PROGRESS = 0.7;
    public static final int GLOBAL_CAP = 30;

    // ---- Spawn distances (blocks) ----
    public static final double PATROL_MIN = 24.0;
    public static final double PATROL_MAX = 40.0;
    public static final double AMBUSH_MIN = 16.0;
    public static final double AMBUSH_MAX = 26.0;
    public static final double WATER_MIN = 12.0;
    public static final double WATER_MAX = 24.0;

    /** Nothing spawns closer than this to any player. */
    public static final double PLAYER_CLEARANCE = 12.0;

    // ---- Warnings (ticks between the first cue and the spawn) ----
    public static final int PATROL_WARN_TICKS = 160;
    public static final int AMBUSH_WARN_TICKS = 120;
    public static final int WATER_WARN_TICKS = 100;
    public static final int CROAK_WARN_TICKS = 120;
    public static final int FORCE_WARN_TICKS = 60;

    // ---- Cleaning up ----
    public static final long MOB_LIFETIME_TICKS = 6000L;
    public static final double DESPAWN_FAR_BLOCKS = 96.0;
    public static final double LINGER_NEAR_BLOCKS = 24.0;

    // ---- The Croak ----
    public static final double CROAK_MIN_AGITATION = 60.0;
    public static final double CROAK_INTERVAL_MAX_SECONDS = 45.0;
    public static final double CROAK_INTERVAL_MIN_SECONDS = 18.0;
    public static final double CROAK_PATROL_CHANCE = 0.8;
    public static final double CROAK_HEAR_BLOCKS = 48.0;

    // ---- Storms ----
    public static final double STORM_MIN_AGITATION = 70.0;
    public static final long STORM_COOLDOWN_TICKS = 24000L;
    public static final int STORM_WARN_TICKS = 600;
    public static final double STORM_CHANCE_PER_SECOND = 0.02;
    public static final int STORM_DURATION_TICKS = 6000;

    // ---- Traveler's camps ----
    public static final double CAMP_CHANCE = 0.6;
    public static final double CAMP_MIN_SPACING = 150.0;
    public static final int MAX_CAMPS = 12;
    public static final int CAMP_AHEAD_MIN = 50;
    public static final int CAMP_AHEAD_MAX = 80;
    public static final double CAMP_CALM_RADIUS = 10.0;
    public static final double CAMP_CALM_PER_SECOND = 0.1;
}