package com.twofrogs.state;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where every structure goes, which ones have been built, where their clue containers (or, for altars, their
 * pedestals) are, which way their entrances face, which altars have been activated, and whether the Binding is
 * complete. Saved with the world. Site positions are the top ground block at the site's center.
 */
public class WorldPlanState extends SavedData {

    private static final String DATA_NAME = "the_two_frogs_plan";

    public enum SiteType {
        HABITAT, SWAMP_RUIN_A, SWAMP_RUIN_B, INTERMEDIATE_A, INTERMEDIATE_B, ALTAR_A, ALTAR_B
    }

    private final Map<SiteType, BlockPos> sites = new EnumMap<>(SiteType.class);
    private final Map<SiteType, BlockPos> containers = new EnumMap<>(SiteType.class);
    private final Map<SiteType, Integer> facing = new EnumMap<>(SiteType.class);
    private final Set<SiteType> built = EnumSet.noneOf(SiteType.class);
    private final Set<SiteType> activated = EnumSet.noneOf(SiteType.class);
    private boolean planned = false;
    private boolean frogsSpawned = false;
    private boolean bindingComplete = false;

    public static WorldPlanState get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(WorldPlanState::load, WorldPlanState::new, DATA_NAME);
    }

    public static WorldPlanState load(CompoundTag tag) {
        WorldPlanState state = new WorldPlanState();
        state.planned = tag.getBoolean("Planned");
        state.frogsSpawned = tag.getBoolean("FrogsSpawned");
        state.bindingComplete = tag.getBoolean("BindingComplete");
        for (SiteType type : SiteType.values()) {
            readPos(tag, "Site" + type.name(), state.sites, type);
            readPos(tag, "Container" + type.name(), state.containers, type);
            if (tag.contains("Facing" + type.name())) {
                state.facing.put(type, tag.getInt("Facing" + type.name()));
            }
            if (tag.getBoolean("Built" + type.name())) {
                state.built.add(type);
            }
            if (tag.getBoolean("Activated" + type.name())) {
                state.activated.add(type);
            }
        }
        return state;
    }

    private static void readPos(CompoundTag tag, String key, Map<SiteType, BlockPos> target, SiteType type) {
        if (tag.contains(key, Tag.TAG_COMPOUND)) {
            CompoundTag t = tag.getCompound(key);
            target.put(type, new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z")));
        }
    }

    private static void writePos(CompoundTag tag, String key, BlockPos pos) {
        if (pos == null) {
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putInt("X", pos.getX());
        t.putInt("Y", pos.getY());
        t.putInt("Z", pos.getZ());
        tag.put(key, t);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("Planned", planned);
        tag.putBoolean("FrogsSpawned", frogsSpawned);
        tag.putBoolean("BindingComplete", bindingComplete);
        for (SiteType type : SiteType.values()) {
            writePos(tag, "Site" + type.name(), sites.get(type));
            writePos(tag, "Container" + type.name(), containers.get(type));
            if (facing.containsKey(type)) {
                tag.putInt("Facing" + type.name(), facing.get(type));
            }
            tag.putBoolean("Built" + type.name(), built.contains(type));
            tag.putBoolean("Activated" + type.name(), activated.contains(type));
        }
        return tag;
    }

    public Optional<BlockPos> getSite(SiteType type) {
        return Optional.ofNullable(sites.get(type));
    }

    public void setSite(SiteType type, BlockPos pos) {
        sites.put(type, pos.immutable());
        setDirty();
    }

    /** Where the clue container of this site is (for altars: the pedestal block). */
    public Optional<BlockPos> getContainer(SiteType type) {
        return Optional.ofNullable(containers.get(type));
    }

    public void setContainer(SiteType type, BlockPos pos) {
        containers.put(type, pos.immutable());
        setDirty();
    }

    /** The world direction the entrance of this structure faces (used to write truthful clues). */
    public Optional<Direction> getFacing(SiteType type) {
        Integer value = facing.get(type);
        return value == null ? Optional.empty() : Optional.of(Direction.from2DDataValue(value));
    }

    public void setFacing(SiteType type, Direction direction) {
        facing.put(type, direction.get2DDataValue());
        setDirty();
    }

    public boolean isBuilt(SiteType type) {
        return built.contains(type);
    }

    public void markBuilt(SiteType type) {
        built.add(type);
        setDirty();
    }

    /** True once the frog belonging to this altar has been sealed there. */
    public boolean isActivated(SiteType type) {
        return activated.contains(type);
    }

    public void markActivated(SiteType type) {
        activated.add(type);
        setDirty();
    }

    public boolean isBindingComplete() {
        return bindingComplete;
    }

    public void setBindingComplete(boolean value) {
        this.bindingComplete = value;
        setDirty();
    }

    public boolean isPlanned() {
        return planned;
    }

    public void setPlanned(boolean value) {
        this.planned = value;
        setDirty();
    }

    public boolean isFrogsSpawned() {
        return frogsSpawned;
    }

    public void setFrogsSpawned(boolean value) {
        this.frogsSpawned = value;
        setDirty();
    }

    /** Forgets the whole plan (debug use). Does not delete anything already built or spawned. */
    public void clear() {
        sites.clear();
        containers.clear();
        facing.clear();
        built.clear();
        activated.clear();
        planned = false;
        frogsSpawned = false;
        bindingComplete = false;
        setDirty();
    }
}