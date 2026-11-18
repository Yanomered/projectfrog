package com.twofrogs.encounter;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The live encounter settings, the placed camps and the time of the last storm. Saved with the world. */
public class EncounterSettings extends SavedData {

    private static final String DATA_NAME = "the_two_frogs_encounters";

    private boolean enabled = true;
    private double frequency = 1.0;
    private double difficulty = 1.0;
    private long lastStormTick = -1_000_000L;
    private final List<BlockPos> camps = new ArrayList<>();

    public static EncounterSettings get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(EncounterSettings::load, EncounterSettings::new, DATA_NAME);
    }

    public static EncounterSettings load(CompoundTag tag) {
        EncounterSettings s = new EncounterSettings();
        if (tag.contains("Enabled")) {
            s.enabled = tag.getBoolean("Enabled");
        }
        if (tag.contains("Frequency")) {
            s.frequency = tag.getDouble("Frequency");
        }
        if (tag.contains("Difficulty")) {
            s.difficulty = tag.getDouble("Difficulty");
        }
        if (tag.contains("LastStorm")) {
            s.lastStormTick = tag.getLong("LastStorm");
        }
        for (long packed : tag.getLongArray("Camps")) {
            s.camps.add(BlockPos.of(packed));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("Enabled", enabled);
        tag.putDouble("Frequency", frequency);
        tag.putDouble("Difficulty", difficulty);
        tag.putLong("LastStorm", lastStormTick);
        long[] packed = new long[camps.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = camps.get(i).asLong();
        }
        tag.putLongArray("Camps", packed);
        return tag;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        setDirty();
    }

    public double getFrequency() {
        return frequency;
    }

    public void setFrequency(double value) {
        this.frequency = value;
        setDirty();
    }

    public double getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(double value) {
        this.difficulty = value;
        setDirty();
    }

    public long getLastStormTick() {
        return lastStormTick;
    }

    public void setLastStormTick(long tick) {
        this.lastStormTick = tick;
        setDirty();
    }

    public List<BlockPos> camps() {
        return camps;
    }

    public void addCamp(BlockPos pos) {
        camps.add(pos.immutable());
        setDirty();
    }

    /** The nearest camp within the given horizontal distance, if any. */
    public Optional<BlockPos> nearestCamp(BlockPos pos, double radius) {
        BlockPos best = null;
        double bestDistance = radius * radius;
        for (BlockPos camp : camps) {
            double dx = camp.getX() - pos.getX();
            double dz = camp.getZ() - pos.getZ();
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                best = camp;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }
}