package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Swamp Ruin B: the "Drowned Watch Post". The stump of a round tower with a collapsed ring wall.
 * A spruce trapdoor in the floor leads down a ladder to a small cellar holding the clue chest.
 */
public final class SwampRuinB {

    public static final int HALF = 5;

    private SwampRuinB() {
    }

    /** Builds the ruin and returns the world position of the clue chest. */
    public static BlockPos build(StructureContext ctx) {
        ctx.prepare(HALF, HALF, 10);

        // Floor and ring wall (circular footprint)
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance <= 3.4) {
                    ctx.set(dx, 0, dz, ctx.chance(0.75) ? ctx.weatheredCobble()
                            : Blocks.SPRUCE_PLANKS.defaultBlockState());
                } else if (distance <= 4.8) {
                    ctx.set(dx, 0, dz, ctx.weatheredCobble());
                    int height = 6 - ctx.random.nextInt(5);   // 2 to 6
                    if (dz < 0) {
                        height = Math.max(1, height - 2);     // the north side has fallen the most
                    }
                    if (dz >= 3 && Math.abs(dx) <= 0) {
                        height = 0;                           // the doorway
                    }
                    for (int dy = 1; dy <= height; dy++) {
                        ctx.set(dx, dy, dz, ctx.weatheredCobble());
                    }
                }
            }
        }

        // The cellar under the floor: a lined 3 x 3 room, 3 blocks deep
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -4; dy <= -1; dy++) {
                    boolean interior = Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= -3;
                    if (interior) {
                        ctx.setAir(dx, dy, dz);
                    } else {
                        ctx.set(dx, dy, dz, ctx.chance(0.35)
                                ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                                : Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
            }
        }
        // A solid ceiling over the cellar, with the trapdoor hatch in it
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ctx.set(dx, 0, dz, Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        BlockState hatch = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.NORTH)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.OPEN, false);
        ctx.set(0, 0, -1, hatch);

        // Ladder down the north wall of the cellar (it clings to the lining at z = -2)
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
        for (int dy = -3; dy <= -1; dy++) {
            ctx.set(0, dy, -1, ladder);
        }

        // The clue chest and a few cobwebs
        ctx.set(1, -3, 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        ctx.set(-1, -3, 1, Blocks.COBWEB.defaultBlockState());
        ctx.set(-1, -1, 1, Blocks.COBWEB.defaultBlockState());

        // Moss and ferns on the tower floor, and a mossy rug over the hatch area
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.sqrt(dx * dx + dz * dz) > 3.2 || !ctx.isAir(dx, 1, dz) || (dx == 0 && dz == -1)) {
                    continue;
                }
                double r = ctx.random.nextDouble();
                if (r < 0.30) {
                    ctx.set(dx, 1, dz, Blocks.MOSS_CARPET.defaultBlockState());
                } else if (r < 0.38) {
                    ctx.set(dx, 1, dz, Blocks.FERN.defaultBlockState());
                } else if (r < 0.42) {
                    ctx.set(dx, 1, dz, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                }
            }
        }

        ctx.anchor(HALF, HALF, 2); // nothing may hang in the air (the cellar columns are left alone)
        dressVines(ctx, 0.22);
        scatterGround(ctx);

        return ctx.world(1, -3, 1);
    }

    private static BlockState vine(Direction support) {
        return Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(support), true);
    }

    /** Vines on the outer faces of the ring wall. */
    private static void dressVines(StructureContext ctx, double chance) {
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = 1; dy <= 6; dy++) {
                    if (ctx.isAir(dx, dy, dz)) {
                        continue;
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int nx = dx + d.getStepX();
                        int nz = dz + d.getStepZ();
                        if (Math.sqrt(nx * nx + nz * nz) > 4.8 && ctx.isAir(nx, dy, nz) && ctx.chance(chance)) {
                            ctx.set(nx, dy, nz, vine(d.getOpposite()));
                        }
                    }
                }
            }
        }
    }

    private static void scatterGround(StructureContext ctx) {
        for (int dx = -HALF - 3; dx <= HALF + 3; dx++) {
            for (int dz = -HALF - 3; dz <= HALF + 3; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance <= 5.5 || distance > 8.5 || !ctx.chance(0.28)) {
                    continue;
                }
                int groundY = ctx.groundTop(dx, dz);
                BlockPos above = ctx.world(dx, 0, dz).atY(groundY + 1);
                BlockPos ground = ctx.world(dx, 0, dz).atY(groundY);
                if (!ctx.level.getBlockState(above).isAir() || !ctx.level.getFluidState(ground).isEmpty()) {
                    continue;
                }
                double r = ctx.random.nextDouble();
                BlockState plant = r < 0.45 ? Blocks.FERN.defaultBlockState()
                        : r < 0.80 ? Blocks.MOSS_CARPET.defaultBlockState()
                        : Blocks.COBBLESTONE.defaultBlockState();
                ctx.level.setBlock(above, plant, 2);
            }
        }
    }
}