package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Swamp Ruin A: the "Sunken Chapel". A 9 x 11 stone brick room, half collapsed and overgrown.
 * The entrance is a narrow gap in the west wall, hidden behind a curtain of vines.
 * The clue barrel is sealed in a nook in the east wall behind a cracked stone brick, and a sign
 * on the wall tells the visitor to break that brick.
 */
public final class SwampRuinA {

    public static final int HALF_X = 4;
    public static final int HALF_Z = 5;

    private SwampRuinA() {
    }

    /** Builds the ruin and returns the world position of the clue barrel. */
    public static BlockPos build(StructureContext ctx) {
        ctx.prepare(HALF_X, HALF_Z, 10);

        // Floor
        for (int dx = -HALF_X; dx <= HALF_X; dx++) {
            for (int dz = -HALF_Z; dz <= HALF_Z; dz++) {
                double r = ctx.random.nextDouble();
                BlockState floor = r < 0.45 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                        : r < 0.75 ? Blocks.STONE_BRICKS.defaultBlockState()
                        : r < 0.90 ? Blocks.DARK_OAK_PLANKS.defaultBlockState()
                        : Blocks.MOSS_BLOCK.defaultBlockState();
                ctx.set(dx, 0, dz, floor);
            }
        }

        // Walls with random collapse
        for (int dx = -HALF_X; dx <= HALF_X; dx++) {
            for (int dz = -HALF_Z; dz <= HALF_Z; dz++) {
                boolean edge = Math.abs(dx) == HALF_X || Math.abs(dz) == HALF_Z;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(dx) == HALF_X && Math.abs(dz) == HALF_Z;
                int height = 4 - ctx.random.nextInt(4);   // 1 to 4
                if (dz == HALF_Z) {
                    height = Math.max(0, height - 2);     // the south wall has mostly fallen
                }
                if (corner) {
                    height = Math.max(height, 2);
                }
                if (dx == HALF_X && dz >= -4 && dz <= -2) {
                    height = Math.max(height, 3);         // keep the wall around the hidden nook solid
                }
                if (dx == -HALF_X && (dz == -1 || dz == 1)) {
                    height = Math.max(height, 3);         // the wall beside the doorway must hold the lintel up
                }
                if (dx == -HALF_X && dz == 0) {
                    height = 0;                           // the entrance gap
                }
                for (int dy = 1; dy <= height; dy++) {
                    ctx.set(dx, dy, dz, ctx.weatheredBrick());
                }
            }
        }

        // Entrance: a lintel resting on the walls beside it, with a curtain of vines hanging under it
        ctx.set(-HALF_X, 3, 0, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
        ctx.set(-HALF_X, 2, 0, vine(Direction.UP));
        ctx.set(-HALF_X, 1, 0, vine(Direction.UP));

        // Broken pillars
        pillar(ctx, -2, -2, 3);
        pillar(ctx, 2, -1, 2);
        pillar(ctx, -1, 2, 4);
        pillar(ctx, 2, 3, 1);

        // Rubble, moss, ferns and cobwebs inside
        for (int dx = -HALF_X + 1; dx <= HALF_X - 1; dx++) {
            for (int dz = -HALF_Z + 1; dz <= HALF_Z - 1; dz++) {
                if (!ctx.isAir(dx, 1, dz)) {
                    continue;
                }
                double r = ctx.random.nextDouble();
                if (r < 0.08) {
                    ctx.set(dx, 1, dz, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                } else if (r < 0.14) {
                    ctx.set(dx, 1, dz, Blocks.COBBLESTONE.defaultBlockState());
                } else if (r < 0.40) {
                    ctx.set(dx, 1, dz, Blocks.MOSS_CARPET.defaultBlockState());
                } else if (r < 0.48) {
                    ctx.set(dx, 1, dz, Blocks.FERN.defaultBlockState());
                } else if (r < 0.52) {
                    ctx.set(dx, 2, dz, Blocks.COBWEB.defaultBlockState());
                }
            }
        }

        // The hidden clue nook: a barrel in the east wall, plugged on the inside with a cracked brick
        ctx.set(HALF_X, 1, -3, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.WEST));
        ctx.set(HALF_X - 1, 1, -3, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
        ctx.set(HALF_X + 1, 1, -3, Blocks.MOSSY_STONE_BRICKS.defaultBlockState()); // closes the outside
        ctx.set(HALF_X, 2, -3, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
        ctx.set(HALF_X, 1, -4, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
        ctx.set(HALF_X, 1, -2, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
        ctx.set(HALF_X - 1, 2, -3, Blocks.COBWEB.defaultBlockState());

        placeSign(ctx);

        // Nothing may hang in the air: fill any gap under the walls and the nook plug
        ctx.anchor(HALF_X, HALF_Z, -1);

        // Vines on the outside of the walls, and greenery around the ruin
        dressVines(ctx, 0.20);
        scatterGround(ctx);

        return ctx.world(HALF_X, 1, -3);
    }

    /** A waxed (uneditable) wall sign next to the nook that says what to do. */
    private static void placeSign(StructureContext ctx) {
        ctx.set(HALF_X - 1, 2, -2, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.WEST));

        BlockPos signPos = ctx.world(HALF_X - 1, 2, -2);
        if (ctx.level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            SignText text = new SignText()
                    .setMessage(0, Component.literal("Break the"))
                    .setMessage(1, Component.literal("cracked stone"))
                    .setMessage(2, Component.literal("to find what"))
                    .setMessage(3, Component.literal("was left here"));
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.setChanged();
        }
    }

    private static void pillar(StructureContext ctx, int dx, int dz, int height) {
        for (int dy = 1; dy <= height; dy++) {
            ctx.set(dx, dy, dz, ctx.weatheredBrick());
        }
    }

    private static BlockState vine(Direction support) {
        return Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(support), true);
    }

    /** Places vines on the outer faces of the wall blocks (they cling to the wall). */
    private static void dressVines(StructureContext ctx, double chance) {
        for (int dx = -HALF_X; dx <= HALF_X; dx++) {
            for (int dz = -HALF_Z; dz <= HALF_Z; dz++) {
                boolean edge = Math.abs(dx) == HALF_X || Math.abs(dz) == HALF_Z;
                if (!edge) {
                    continue;
                }
                for (int dy = 1; dy <= 4; dy++) {
                    if (ctx.isAir(dx, dy, dz)) {
                        continue;
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int nx = dx + d.getStepX();
                        int nz = dz + d.getStepZ();
                        boolean outside = Math.abs(nx) > HALF_X || Math.abs(nz) > HALF_Z;
                        if (outside && ctx.isAir(nx, dy, nz) && ctx.chance(chance)) {
                            ctx.set(nx, dy, nz, vine(d.getOpposite()));
                        }
                    }
                }
            }
        }
    }

    private static void scatterGround(StructureContext ctx) {
        for (int dx = -HALF_X - 3; dx <= HALF_X + 3; dx++) {
            for (int dz = -HALF_Z - 3; dz <= HALF_Z + 3; dz++) {
                boolean insideFootprint = Math.abs(dx) <= HALF_X + 1 && Math.abs(dz) <= HALF_Z + 1;
                if (insideFootprint || !ctx.chance(0.30)) {
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
                        : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
                ctx.level.setBlock(above, plant, 2);
            }
        }
    }
}