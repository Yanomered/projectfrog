package com.twofrogs.frog;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;

/**
 * Stops a player from instantly re-filling a cell a frog has just broken open.
 * Only that exact cell, and only for a few seconds. Everything else about placing blocks is untouched.
 */
public final class PlacementGuard {

    private PlacementGuard() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide || !(world instanceof ServerLevel level)) {
                return InteractionResult.PASS;
            }
            if (!(player.getItemInHand(hand).getItem() instanceof BlockItem)) {
                return InteractionResult.PASS;
            }
            BlockPos placePos = hitResult.getBlockPos().relative(hitResult.getDirection());
            if (CellClaims.isClaimed(level, placePos)) {
                player.displayClientMessage(Component.literal("Something is moving there."), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }
}