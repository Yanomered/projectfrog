package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Altar B: the "Moon Altar". A low, stepped dome of cool deepslate that rises in four tiers to a summit
 * binding circle with a pedestal. A wide stair strip climbs the south side (the entrance), between two
 * pylons that carry the waxed signs. Six uneven monoliths ring the summit.
 */
public final class AltarB {

    public static final int HALF = 12;
    private static final double OUTER = 11.5;

    private AltarB() {
    }

    private static int tierHeight(double r) {
        if (r <= 4.2) {
            return 4;
        }
        if (r <= 5.8) {
            return 3;
        }
        if (r <= 7.4) {
            return 2;
        }
        if (r <= 9.0) {
            return 1;
        }
        return 0;
    }

    private static BlockState surface(StructureContext ctx, int dx, int dz, double r) {
        double v = ctx.random.nextDouble();
        if (r <= 3.6) {
            if (r <= 1.3) {
                return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            }
            int sector = Math.floorMod((int) Math.floor(Math.atan2(dz, dx) / (Math.PI / 3.0)), 2);
            return sector == 0 ? Blocks.BLUE_TERRACOTTA.defaultBlockState()
                    : Blocks.DEEPSLATE_TILES.defaultBlockState();
        }
        if (r <= 9.0) {
            if (v < 0.55) {
                return Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            }
            return v < 0.85 ? Blocks.POLISHED_DEEPSLATE.defaultBlockState()
                    : Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
        }
        if (v < 0.55) {
            return Blocks.DEEPSLATE_TILES.defaultBlockState();
        }
        if (v < 0.80) {
            return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        }
        return v < 0.95 ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState();
    }

    /** Builds the altar and returns the world position of the pedestal block. */
    public static BlockPos build(StructureContext ctx) {
        ctx.prepareOval(OUTER, OUTER, 18);

        // The stepped dome: a solid core with a patterned top
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                double r = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (r > OUTER) {
                    continue;
                }
                int h = tierHeight(r);
                for (int dy = 0; dy < h; dy++) {
                    ctx.set(dx, dy, dz, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
                }
                ctx.set(dx, h, dz, surface(ctx, dx, dz, r));
            }
        }

        // The stair strip up the south side: wherever the ground rises toward the middle, a stair block
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 9; dz >= 3; dz--) {
                int here = tierHeight(Math.sqrt((double) dx * dx + (double) dz * dz));
                int south = tierHeight(Math.sqrt((double) dx * dx + (double) (dz + 1) * (dz + 1)));
                if (here > south) {
                    ctx.set(dx, here, dz, Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState()
                            .setValue(StairBlock.FACING, Direction.NORTH));
                }
            }
        }

        // The pedestal on the summit
        ctx.set(0, 5, 0, Blocks.CHISELED_DEEPSLATE.defaultBlockState());

        // Soul campfires on the third tier
        int[][] fires = {{5, 0}, {-5, 0}, {0, 5}, {0, -5}};
        for (int[] f : fires) {
            if (f[0] == 0 && f[1] == 5) {
                continue; // keep the stairs clear
            }
            int h = tierHeight(Math.sqrt((double) f[0] * f[0] + (double) f[1] * f[1]));
            ctx.set(f[0], h + 1, f[1], Blocks.SOUL_CAMPFIRE.defaultBlockState());
        }

        // Six uneven monoliths around the summit
        double start = ctx.random.nextDouble() * Math.PI * 2.0;
        for (int i = 0; i < 6; i++) {
            double angle = start + i * (Math.PI / 3.0);
            int mx = (int) Math.round(Math.cos(angle) * 6.4);
            int mz = (int) Math.round(Math.sin(angle) * 6.4);
            if (Math.abs(mx) <= 1 && mz > 0) {
                continue; // not across the stairs
            }
            int base = tierHeight(Math.sqrt((double) mx * mx + (double) mz * mz));
            int height = 3 + ctx.random.nextInt(2);
            for (int dy = base + 1; dy <= base + height; dy++) {
                ctx.set(mx, dy, mz, dy == base + height ? Blocks.CHISELED_DEEPSLATE.defaultBlockState()
                        : Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            }
            if (i % 2 == 0) {
                ctx.set(mx, base + height + 1, mz, Blocks.SOUL_LANTERN.defaultBlockState());
            }
        }

        // The two entrance pylons and the signs that explain the rite
        for (int side : new int[]{-3, 3}) {
            for (int dy = 1; dy <= 4; dy++) {
                ctx.set(side, dy, 10, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            }
            ctx.set(side, 5, 10, Blocks.SOUL_LANTERN.defaultBlockState());
        }
        Signs.wall(ctx, -2, 2, 10, Direction.EAST, "Bring the bound", "creature of", "this altar into", "the circle.");
        Signs.wall(ctx, -2, 3, 10, Direction.EAST, "It will fight", "to break free.", "Keep it on", "its lead.");
        Signs.wall(ctx, 2, 2, 10, Direction.WEST, "Lay a hand on", "the stone, and", "hold firm until", "it falls quiet.");

        ctx.anchor(HALF, HALF, -1);

        return ctx.world(0, 5, 0);
    }
}