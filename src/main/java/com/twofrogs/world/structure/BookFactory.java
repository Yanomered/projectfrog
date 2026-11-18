package com.twofrogs.world.structure;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Makes written books (used for clues). */
public final class BookFactory {

    /** The tag on a clue book that says which site it belongs to. */
    public static final String CLUE_TAG = "TwoFrogsClue";

    private BookFactory() {
    }

    public static ItemStack writtenBook(String title, String author, List<String> pages) {
        return writtenBook(title, author, pages, null);
    }

    public static ItemStack writtenBook(String title, String author, List<String> pages, String clueTag) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString("title", title);
        tag.putString("author", author);
        tag.putInt("generation", 3); // "Tattered": cannot be copied
        if (clueTag != null) {
            tag.putString(CLUE_TAG, clueTag);
        }

        ListTag list = new ListTag();
        for (String page : pages) {
            list.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(page))));
        }
        tag.put("pages", list);
        return stack;
    }
}