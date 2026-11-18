package com.twofrogs.encounter;

import com.twofrogs.frog.FrogTerrain;
import com.twofrogs.world.structure.BookFactory;
import com.twofrogs.world.structure.Loot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * A small abandoned traveler's camp. Every piece follows the ground on its own (nothing is flattened), so it
 * sits naturally in the land. The campfire calms a captured frog, and a chest holds modest supplies.
 */
public final class EncounterCamp {

    private EncounterCamp() {
    }

    private static BlockPos ground(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** True if 'pos' (the cell just above the ground) is a good place: dry, solid, and fairly level. */
    public static boolean suitable(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !FrogTerrain.solid(level, pos.below())
                || !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) {
            return false;
        }
        int[][] corners = {{3, 3}, {-3, 3}, {3, -3}, {-3, -3}};
        for (int[] c : corners) {
            BlockPos p = pos.offset(c[0], 0, c[1]);
            if (!level.hasChunkAt(p)) {
                return false;
            }
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
            if (Math.abs(h - pos.getY()) > 2 || !level.getFluidState(new BlockPos(p.getX(), h - 1, p.getZ())).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static void build(ServerLevel level, BlockPos center, RandomSource random) {
        int cx = center.getX();
        int cz = center.getZ();

        // The fire, ringed with low stone
        level.setBlock(ground(level, cx, cz), Blocks.CAMPFIRE.defaultBlockState(), 3);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || random.nextDouble() < 0.2) {
                    continue;
                }
                level.setBlock(ground(level, cx + dx, cz + dz), Blocks.COBBLESTONE_SLAB.defaultBlockState(), 3);
            }
        }

        // Two logs to sit on
        for (int side : new int[]{-2, 2}) {
            level.setBlock(ground(level, cx + side, cz),
                    Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z), 3);
        }

        // Torches
        level.setBlock(ground(level, cx - 3, cz + 2), Blocks.TORCH.defaultBlockState(), 3);
        level.setBlock(ground(level, cx + 2, cz - 2), Blocks.TORCH.defaultBlockState(), 3);

        // A sign
        BlockPos signPos = ground(level, cx + 3, cz + 2);
        level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, random.nextInt(16)), 3);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            SignText text = new SignText()
                    .setMessage(0, Component.literal("Camp left in"))
                    .setMessage(1, Component.literal("haste. Rest by"))
                    .setMessage(2, Component.literal("the fire; it"))
                    .setMessage(3, Component.literal("calms the frog."));
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.setChanged();
        }

        // The supplies chest
        BlockPos chestPos = ground(level, cx, cz - 3);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), 3);
        BlockEntity entity = level.getBlockEntity(chestPos);
        if (entity instanceof Container container) {
            container.setItem(0, BookFactory.writtenBook("Torn Page", "A Tired Bearer", List.of(
                    "We carried it as far as we dared. It would not stop sweating, and the dark kept answering "
                            + "its croak.\n\nWe left in the night.",
                    "Whoever finds this: rest by the fire. The embers calm it. Green slime helps.\n\n"
                            + "Do not let the two meet.")));
            Loot.scatter(level, chestPos, random, List.of(
                    new ItemStack(Items.BREAD, 2 + random.nextInt(3)),
                    new ItemStack(Items.TORCH, 4 + random.nextInt(5)),
                    new ItemStack(Items.ARROW, 4 + random.nextInt(5)),
                    new ItemStack(Items.COOKED_CHICKEN, 1 + random.nextInt(2)),
                    new ItemStack(Items.SLIME_BALL, 1 + random.nextInt(2)),
                    new ItemStack(Items.LEAD, 1)));
        }
    }
}