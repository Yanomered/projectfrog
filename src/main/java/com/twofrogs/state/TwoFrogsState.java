package com.twofrogs.state;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Optional;
import java.util.UUID;

/**
 * Everything the mod must remember about the two special frogs.
 * Saved inside the world folder, so it survives restarts and
 * is shared by all players on a server.
 */
public class TwoFrogsState extends SavedData {

    private static final String DATA_NAME = "the_two_frogs";
    private static final String OVERWORLD_ID = "minecraft:overworld";

    public enum FrogRole {
        A, B
    }

    public enum FrogBehavior {
        DORMANT, CAPTURED, ESCAPED, SEALED
    }

    /** A position in a specific dimension. The dimension is stored as text, e.g. "minecraft:overworld". */
    public record FrogLocation(String dimension, BlockPos pos) {
        @Override
        public String toString() {
            return dimension + " at " + pos.toShortString();
        }
    }

    private UUID frogA = null;
    private UUID frogB = null;

    // Cumulative agitation. Never reset automatically.
    private double agitationA = 0.0;
    private double agitationB = 0.0;

    private BlockPos habitatCenter = null;
    private int habitatRadius = 0;
    private boolean apocalypseTriggered = false;

    // Indexed by FrogRole.ordinal(): 0 = A, 1 = B
    private final FrogLocation[] lastKnown = new FrogLocation[2];
    private final FrogLocation[] safe = new FrogLocation[2];
    private final FrogBehavior[] behavior = {FrogBehavior.DORMANT, FrogBehavior.DORMANT};
    private final int[] escapeCount = new int[2];
    private final int[] recaptureCount = new int[2];
    /** Game time when agitation was last updated. -1 means the agitation clock has not started. */
    private final long[] lastAgitationTick = {-1L, -1L};
    /** Total ticks spent away from the habitat (not dormant). */
    private final long[] awayTicks = new long[2];

    // ---------- Loading and saving ----------

    /** Gets (or creates) the state for this world. Always stored in the Overworld. */
    public static TwoFrogsState get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(TwoFrogsState::load, TwoFrogsState::new, DATA_NAME);
    }

    public static TwoFrogsState load(CompoundTag tag) {
        TwoFrogsState state = new TwoFrogsState();
        if (tag.hasUUID("FrogA")) {
            state.frogA = tag.getUUID("FrogA");
        }
        if (tag.hasUUID("FrogB")) {
            state.frogB = tag.getUUID("FrogB");
        }
        state.agitationA = tag.getDouble("AgitationA");
        state.agitationB = tag.getDouble("AgitationB");
        if (tag.getBoolean("HasHabitat")) {
            state.habitatCenter = new BlockPos(
                    tag.getInt("HabitatX"), tag.getInt("HabitatY"), tag.getInt("HabitatZ"));
        }
        state.habitatRadius = tag.getInt("HabitatRadius");
        state.apocalypseTriggered = tag.getBoolean("ApocalypseTriggered");

        FrogBehavior[] behaviors = FrogBehavior.values();
        for (FrogRole role : FrogRole.values()) {
            int i = role.ordinal();
            state.lastKnown[i] = readLocation(tag, "LastKnown" + role);
            state.safe[i] = readLocation(tag, "Safe" + role);
            int b = tag.getInt("Behavior" + role);
            state.behavior[i] = behaviors[Math.max(0, Math.min(b, behaviors.length - 1))];
            state.escapeCount[i] = tag.getInt("Escapes" + role);
            state.recaptureCount[i] = tag.getInt("Recaptures" + role);
            state.lastAgitationTick[i] = tag.contains("AgitationClock" + role)
                    ? tag.getLong("AgitationClock" + role) : -1L;
            state.awayTicks[i] = tag.getLong("AwayTicks" + role);
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (frogA != null) {
            tag.putUUID("FrogA", frogA);
        }
        if (frogB != null) {
            tag.putUUID("FrogB", frogB);
        }
        tag.putDouble("AgitationA", agitationA);
        tag.putDouble("AgitationB", agitationB);
        tag.putBoolean("HasHabitat", habitatCenter != null);
        if (habitatCenter != null) {
            tag.putInt("HabitatX", habitatCenter.getX());
            tag.putInt("HabitatY", habitatCenter.getY());
            tag.putInt("HabitatZ", habitatCenter.getZ());
        }
        tag.putInt("HabitatRadius", habitatRadius);
        tag.putBoolean("ApocalypseTriggered", apocalypseTriggered);

        for (FrogRole role : FrogRole.values()) {
            int i = role.ordinal();
            writeLocation(tag, "LastKnown" + role, lastKnown[i]);
            writeLocation(tag, "Safe" + role, safe[i]);
            tag.putInt("Behavior" + role, behavior[i].ordinal());
            tag.putInt("Escapes" + role, escapeCount[i]);
            tag.putInt("Recaptures" + role, recaptureCount[i]);
            tag.putLong("AgitationClock" + role, lastAgitationTick[i]);
            tag.putLong("AwayTicks" + role, awayTicks[i]);
        }
        return tag;
    }

    private static void writeLocation(CompoundTag tag, String key, FrogLocation loc) {
        if (loc == null) {
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putString("Dim", loc.dimension());
        t.putInt("X", loc.pos().getX());
        t.putInt("Y", loc.pos().getY());
        t.putInt("Z", loc.pos().getZ());
        tag.put(key, t);
    }

    private static FrogLocation readLocation(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag t = tag.getCompound(key);
        return new FrogLocation(t.getString("Dim"),
                new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z")));
    }

    // ---------- Frog identity ----------

    public Optional<UUID> getFrogUuid(FrogRole role) {
        return Optional.ofNullable(role == FrogRole.A ? frogA : frogB);
    }

    public void setFrogUuid(FrogRole role, UUID uuid) {
        if (role == FrogRole.A) {
            frogA = uuid;
        } else {
            frogB = uuid;
        }
        setDirty();
    }

    /** Which special frog (if any) does this UUID belong to? */
    public Optional<FrogRole> getRole(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        if (uuid.equals(frogA)) {
            return Optional.of(FrogRole.A);
        }
        if (uuid.equals(frogB)) {
            return Optional.of(FrogRole.B);
        }
        return Optional.empty();
    }

    public boolean isSpecial(UUID uuid) {
        return getRole(uuid).isPresent();
    }

    /** Forgets both frogs and resets all of their progress (debug use). */
    public void clearFrogs() {
        frogA = null;
        frogB = null;
        agitationA = 0.0;
        agitationB = 0.0;
        for (FrogRole role : FrogRole.values()) {
            int i = role.ordinal();
            lastKnown[i] = null;
            safe[i] = null;
            behavior[i] = FrogBehavior.DORMANT;
            escapeCount[i] = 0;
            recaptureCount[i] = 0;
            lastAgitationTick[i] = -1L;
            awayTicks[i] = 0L;
        }
        setDirty();
    }

    // ---------- Location tracking ----------

    public FrogLocation getLastKnown(FrogRole role) {
        return lastKnown[role.ordinal()];
    }

    public void setLastKnown(FrogRole role, FrogLocation loc) {
        if (!loc.equals(lastKnown[role.ordinal()])) {
            lastKnown[role.ordinal()] = loc;
            setDirty();
        }
    }

    /** The last place this frog was standing on solid ground, out of fluid and not burning. */
    public FrogLocation getSafe(FrogRole role) {
        return safe[role.ordinal()];
    }

    public void setSafe(FrogRole role, FrogLocation loc) {
        if (!loc.equals(safe[role.ordinal()])) {
            safe[role.ordinal()] = loc;
            setDirty();
        }
    }

    // ---------- Behavior, escapes, agitation ----------

    public FrogBehavior getBehavior(FrogRole role) {
        return behavior[role.ordinal()];
    }

    public void setBehavior(FrogRole role, FrogBehavior value) {
        behavior[role.ordinal()] = value;
        setDirty();
    }

    public int getEscapeCount(FrogRole role) {
        return escapeCount[role.ordinal()];
    }

    public void incrementEscapes(FrogRole role) {
        escapeCount[role.ordinal()]++;
        setDirty();
    }

    public int getRecaptureCount(FrogRole role) {
        return recaptureCount[role.ordinal()];
    }

    public void incrementRecaptures(FrogRole role) {
        recaptureCount[role.ordinal()]++;
        setDirty();
    }

    public long getLastAgitationTick(FrogRole role) {
        return lastAgitationTick[role.ordinal()];
    }

    public void setLastAgitationTick(FrogRole role, long tick) {
        lastAgitationTick[role.ordinal()] = tick;
        setDirty();
    }

    public long getAwayTicks(FrogRole role) {
        return awayTicks[role.ordinal()];
    }

    public void addAwayTicks(FrogRole role, long ticks) {
        awayTicks[role.ordinal()] += ticks;
        setDirty();
    }

    public double getAgitation(FrogRole role) {
        return role == FrogRole.A ? agitationA : agitationB;
    }

    public void setAgitation(FrogRole role, double value) {
        if (role == FrogRole.A) {
            agitationA = value;
        } else {
            agitationB = value;
        }
        setDirty();
    }

    // ---------- Habitat and apocalypse ----------

    public Optional<BlockPos> getHabitatCenter() {
        return Optional.ofNullable(habitatCenter);
    }

    public void setHabitatCenter(BlockPos pos) {
        this.habitatCenter = pos;
        setDirty();
    }

    public int getHabitatRadius() {
        return habitatRadius;
    }

    public void setHabitatRadius(int radius) {
        this.habitatRadius = radius;
        setDirty();
    }

    public boolean hasHabitat() {
        return habitatCenter != null && habitatRadius > 0;
    }

    /** True if the location is in the Overworld and within the habitat's horizontal radius. */
    public boolean isInHabitat(FrogLocation loc) {
        if (loc == null || !hasHabitat()) {
            return false;
        }
        if (!OVERWORLD_ID.equals(loc.dimension())) {
            return false;
        }
        long dx = loc.pos().getX() - habitatCenter.getX();
        long dz = loc.pos().getZ() - habitatCenter.getZ();
        return dx * dx + dz * dz <= (long) habitatRadius * habitatRadius;
    }

    public boolean isApocalypseTriggered() {
        return apocalypseTriggered;
    }

    public void setApocalypseTriggered(boolean value) {
        this.apocalypseTriggered = value;
        setDirty();
    }
}