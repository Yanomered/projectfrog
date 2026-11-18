package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/** Scatters loot into a container, keeping the first three slots free for clue items. */
public final class Loot {

    private Loot() {
    }

    public static void scatter(ServerLevel level, BlockPos pos, RandomSource random, List<ItemStack> items) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof Container container)) {
            return;
        }
        int size = container.getContainerSize();
        for (ItemStack stack : items) {
            for (int tries = 0; tries < 40; tries++) {
                int slot = 3 + random.nextInt(Math.max(1, size - 3));
                if (container.getItem(slot).isEmpty()) {
                    container.setItem(slot, stack);
                    break;
                }
            }
        }
        entity.setChanged();
    }
}