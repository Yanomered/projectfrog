package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Intermediate Ruin A: the "Sundered Shrine". A 15 x 15 courtyard of broken pillars around a raised dais
 * with a tall obelisk (topped with a lantern) as a landmark. A broken arch on the south side leads down a
 * staircase to a vault with the clue chest, a barrel of modest supplies, a lectern with a lore book, and signs.
 */
public final class IntermediateRuinA {

    public static final int HALF = 7;

    private IntermediateRuinA() {
    }

    /** Builds the ruin and returns the world position of the clue chest. */
    public static BlockPos build(StructureContext ctx) {
        ctx.prepare(HALF, HALF, 18);

        floor(ctx);
        dais(ctx);
        colonnade(ctx);
        obelisk(ctx);
        BlockPos chest = vault(ctx);

        // Nothing may hang in the air (the columns above the vault are left alone)
        ctx.anchor(HALF, HALF, 3);

        return chest;
    }

    // ---------- The courtyard ----------

    private static void floor(StructureContext ctx) {
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                double r = ctx.random.nextDouble();
                BlockState state;
                if (r < 0.10) {
                    state = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
                } else if (r < 0.18) {
                    state = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                } else if (r < 0.22) {
                    state = Blocks.MOSS_BLOCK.defaultBlockState();
                } else if ((Math.abs(dx) + Math.abs(dz)) % 2 == 0) {
                    state = Blocks.POLISHED_ANDESITE.defaultBlockState();
                } else {
                    state = Blocks.STONE_BRICKS.defaultBlockState();
                }
                ctx.set(dx, 0, dz, state);
            }
        }
    }

    private static void dais(StructureContext ctx) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean inner = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                ctx.set(dx, 1, dz, inner ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                        : Blocks.STONE_BRICK_SLAB.defaultBlockState());
            }
        }
    }

    private static void colonnade(StructureContext ctx) {
        int[][] spots = {
                {-6, -6}, {6, -6}, {-6, 6}, {6, 6},
                {-6, 0}, {6, 0}, {0, -6}, {-6, -3}, {6, -3}, {-3, -6}, {3, -6}, {-6, 3}, {6, 3}
        };
        for (int[] spot : spots) {
            boolean corner = Math.abs(spot[0]) == 6 && Math.abs(spot[1]) == 6;
            int height = 1 + ctx.random.nextInt(5);   // 1 to 5
            if (corner) {
                height = Math.max(height, 3);
            }
            for (int dy = 1; dy <= height; dy++) {
                ctx.set(spot[0], dy, spot[1], dy == height && height >= 4
                        ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : ctx.weatheredBrick());
            }
        }
        // Fallen stones on the floor
        for (int i = 0; i < 14; i++) {
            int dx = ctx.random.nextInt(2 * HALF - 1) - (HALF - 1);
            int dz = ctx.random.nextInt(2 * HALF - 1) - (HALF - 1);
            if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
                continue;
            }
            if (dx >= -1 && dx <= 1 && dz >= 3) {
                continue; // keep the way to the vault clear
            }
            if (ctx.isAir(dx, 1, dz)) {
                ctx.set(dx, 1, dz, ctx.chance(0.5) ? Blocks.STONE_BRICK_SLAB.defaultBlockState()
                        : Blocks.MOSSY_COBBLESTONE.defaultBlockState());
            }
        }
    }

    /** The landmark: a tall obelisk with a lantern on top. */
    private static void obelisk(StructureContext ctx) {
        int height = 11 + ctx.random.nextInt(4);
        for (int dy = 2; dy <= height; dy++) {
            ctx.set(0, dy, 0, dy % 4 == 0 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                    : ctx.weatheredBrick());
        }
        ctx.set(0, height + 1, 0, Blocks.LANTERN.defaultBlockState());
    }

    // ---------- The vault ----------

    private static BlockPos vault(StructureContext ctx) {
        // The room: 5 x 5 inside, 3 high, lined with stone brick
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -4; dy <= -1; dy++) {
                    boolean interior = Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && dy >= -3;
                    if (interior) {
                        ctx.setAir(dx, dy, dz);
                    } else {
                        ctx.set(dx, dy, dz, ctx.chance(0.3) ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                                : Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
            }
        }

        // The staircase down from the arch on the south side
        BlockState stairs = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        for (int k = 0; k <= 3; k++) {
            int z = 6 - k;
            for (int dy = -k + 1; dy <= 0; dy++) {
                ctx.setAir(0, dy, z); // headroom over the step
            }
            ctx.set(0, -k, z, stairs);
            for (int dy = -k - 1; dy <= 1; dy++) {
                ctx.set(-1, dy, z, Blocks.STONE_BRICKS.defaultBlockState());
                ctx.set(1, dy, z, Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        // The broken arch over the top of the stairs
        for (int dy = 2; dy <= 3; dy++) {
            ctx.set(-1, dy, 6, ctx.weatheredBrick());
            ctx.set(1, dy, 6, ctx.weatheredBrick());
        }
        ctx.set(0, 3, 6, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());

        // Containers
        ctx.set(2, -3, -2, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        ctx.set(-2, -3, -2, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        BlockPos chestPos = ctx.world(2, -3, -2);
        BlockPos barrelPos = ctx.world(-2, -3, -2);

        Loot.scatter(ctx.level, barrelPos, ctx.random, List.of(
                new ItemStack(Items.BREAD, 3 + ctx.random.nextInt(3)),
                new ItemStack(Items.TORCH, 6 + ctx.random.nextInt(5)),
                new ItemStack(Items.IRON_INGOT, 2 + ctx.random.nextInt(3)),
                new ItemStack(Items.ARROW, 8 + ctx.random.nextInt(5)),
                new ItemStack(Items.STRING, 3 + ctx.random.nextInt(3)),
                new ItemStack(Items.LEAD, 2)));

        // A lectern with a lore book
        ctx.set(2, -3, 2, Blocks.LECTERN.defaultBlockState()
                .setValue(LecternBlock.FACING, Direction.WEST)
                .setValue(LecternBlock.HAS_BOOK, true));
        if (ctx.level.getBlockEntity(ctx.world(2, -3, 2)) instanceof LecternBlockEntity lectern) {
            lectern.setBook(BookFactory.writtenBook("The Keepers' Rest", "A Forgotten Hand", List.of(
                    "We raised this shrine over the first of the vaults.\n\nTwo creatures were given to us "
                            + "to keep, and a rule: they must never meet.",
                    "So we carried them far apart, one toward the sunrise and one toward the dusk, "
                            + "and raised altars where each could be laid to rest.\n\nThe road is long on purpose.")));
        }

        // Lore signs on the vault walls
        Signs.wall(ctx, 0, -2, -2, Direction.SOUTH, "We bound the", "two apart so", "they would", "never meet.");
        Signs.wall(ctx, -2, -2, 0, Direction.EAST, "A restless frog", "is calmed by", "green slime.", "");

        // Dust and cobwebs
        ctx.set(-2, -1, 2, Blocks.COBWEB.defaultBlockState());
        ctx.set(2, -1, -2, Blocks.COBWEB.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (ctx.isAir(dx, -3, dz) && ctx.chance(0.2)) {
                    ctx.set(dx, -3, dz, Blocks.MOSS_CARPET.defaultBlockState());
                }
            }
        }
        return chestPos;
    }
}