package com.twofrogs.clue;

import com.twofrogs.state.WorldPlanState.SiteType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The generated clue texts, the altar Z ranges and the container types. Saved with the world; never changes once written. */
public class ClueState extends SavedData {

    private static final String DATA_NAME = "the_two_frogs_clues";

    private final Map<SiteType, List<String>> pages = new EnumMap<>(SiteType.class);
    private final Map<SiteType, String> titles = new EnumMap<>(SiteType.class);
    private final Map<SiteType, String> blocks = new EnumMap<>(SiteType.class);
    private final Map<SiteType, int[]> zRanges = new EnumMap<>(SiteType.class);
    private int recoveries = 0;

    public static ClueState get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(ClueState::load, ClueState::new, DATA_NAME);
    }

    public static ClueState load(CompoundTag tag) {
        ClueState state = new ClueState();
        state.recoveries = tag.getInt("Recoveries");
        for (SiteType type : SiteType.values()) {
            String key = "Clue" + type.name();
            if (tag.contains(key, Tag.TAG_COMPOUND)) {
                CompoundTag t = tag.getCompound(key);
                state.titles.put(type, t.getString("Title"));
                state.blocks.put(type, t.getString("Block"));
                List<String> list = new ArrayList<>();
                ListTag pageTags = t.getList("Pages", Tag.TAG_STRING);
                for (int i = 0; i < pageTags.size(); i++) {
                    list.add(pageTags.getString(i));
                }
                state.pages.put(type, list);
            }
            if (tag.contains("ZRange" + type.name(), Tag.TAG_INT_ARRAY)) {
                state.zRanges.put(type, tag.getIntArray("ZRange" + type.name()));
            }
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("Recoveries", recoveries);
        for (SiteType type : SiteType.values()) {
            List<String> list = pages.get(type);
            if (list != null) {
                CompoundTag t = new CompoundTag();
                t.putString("Title", titles.getOrDefault(type, ""));
                t.putString("Block", blocks.getOrDefault(type, ""));
                ListTag pageTags = new ListTag();
                for (String page : list) {
                    pageTags.add(StringTag.valueOf(page));
                }
                t.put("Pages", pageTags);
                tag.put("Clue" + type.name(), t);
            }
            int[] range = zRanges.get(type);
            if (range != null) {
                tag.putIntArray("ZRange" + type.name(), range);
            }
        }
        return tag;
    }

    public boolean isGenerated(SiteType type) {
        return pages.containsKey(type);
    }

    public List<String> getPages(SiteType type) {
        return pages.getOrDefault(type, List.of());
    }

    public String getTitle(SiteType type) {
        return titles.getOrDefault(type, "Weathered Fragment");
    }

    public String getBlockId(SiteType type) {
        return blocks.getOrDefault(type, "");
    }

    public void set(SiteType type, String title, List<String> clueTexts, String blockId) {
        pages.put(type, new ArrayList<>(clueTexts));
        titles.put(type, title);
        blocks.put(type, blockId);
        setDirty();
    }

    /** The Z range written in the intermediate ruin's book for an altar, or null if not chosen yet. */
    public int[] getZRange(SiteType altar) {
        return zRanges.get(altar);
    }

    public void setZRange(SiteType altar, int min, int max) {
        zRanges.put(altar, new int[]{min, max});
        setDirty();
    }

    public int getRecoveries() {
        return recoveries;
    }

    public void incrementRecoveries() {
        recoveries++;
        setDirty();
    }

    public void clear() {
        pages.clear();
        titles.clear();
        blocks.clear();
        zRanges.clear();
        setDirty();
    }
}