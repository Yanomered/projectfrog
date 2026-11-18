package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Altar A: the "Sun Altar". A sunken amphitheater of warm sandstone: a plaza ring of broken pillars around
 * a pit that steps down in three terraces to a sunburst binding circle with a pedestal in the middle.
 * The entrance (a broken gate) faces local south. Three waxed signs explain the rite.
 */
public final class AltarA {

    public static final int HALF = 12;
    private static final double OUTER = 11.5;

    private AltarA() {
    }

    private static int terraceHeight(double r) {
        if (r <= 5.5) {
            return -3;
        }
        if (r <= 7.5) {
            return -2;
        }
        if (r <= 9.5) {
            return -1;
        }
        return 0;
    }

    private static BlockState surface(StructureContext ctx, int dx, int dz, double r) {
        if (r <= 3.6) {
            if (r <= 1.3) {
                return Blocks.SMOOTH_RED_SANDSTONE.defaultBlockState();
            }
            // A sunburst: alternating wedges
            int sector = Math.floorMod((int) Math.floor(Math.atan2(dz, dx) / (Math.PI / 4.0)), 2);
            return sector == 0 ? Blocks.ORANGE_TERRACOTTA.defaultBlockState()
                    : Blocks.YELLOW_TERRACOTTA.defaultBlockState();
        }
        double v = ctx.random.nextDouble();
        if (r <= 5.5) {
            return v < 0.8 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : Blocks.CUT_SANDSTONE.defaultBlockState();
        }
        if (r <= 7.5) {
            return v < 0.7 ? Blocks.CUT_SANDSTONE.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        }
        if (r <= 9.5) {
            return v < 0.12 ? Blocks.CHISELED_SANDSTONE.defaultBlockState() : Blocks.CUT_SANDSTONE.defaultBlockState();
        }
        if (v < 0.60) {
            return Blocks.CUT_SANDSTONE.defaultBlockState();
        }
        return v < 0.88 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : Blocks.SANDSTONE.defaultBlockState();
    }

    /** Builds the altar and returns the world position of the pedestal block. */
    public static BlockPos build(StructureContext ctx) {
        ctx.prepareOval(OUTER, OUTER, 16);

        // The pit and the plaza: terraces stepping down toward the middle, each column filled to a solid body
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                double r = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (r > OUTER) {
                    continue;
                }
                int h = terraceHeight(r);
                for (int dy = h + 1; dy <= 0; dy++) {
                    ctx.setAir(dx, dy, dz);
                }
                for (int dy = -4; dy < h; dy++) {
                    ctx.set(dx, dy, dz, Blocks.SANDSTONE.defaultBlockState());
                }
                ctx.set(dx, h, dz, surface(ctx, dx, dz, r));
            }
        }

        // The pedestal
        ctx.set(0, -2, 0, Blocks.CHISELED_SANDSTONE.defaultBlockState());

        // Four braziers on the middle terrace
        int[][] braziers = {{6, 0}, {-6, 0}, {0, 6}, {0, -6}};
        for (int[] b : braziers) {
            ctx.set(b[0], -1, b[1], Blocks.CAMPFIRE.defaultBlockState());
        }

        // Pillars around the rim: cardinal ones carry the signs, diagonal ones are more broken
        int[][] cardinal = {{10, 0}, {-10, 0}, {0, -10}};
        for (int[] p : cardinal) {
            pillar(ctx, p[0], p[1], 5);
        }
        ctx.set(10, 6, 0, Blocks.LANTERN.defaultBlockState());
        ctx.set(-10, 6, 0, Blocks.LANTERN.defaultBlockState());
        int[][] diagonal = {{7, 7}, {-7, 7}, {7, -7}, {-7, -7}};
        for (int[] p : diagonal) {
            pillar(ctx, p[0], p[1], 3 + ctx.random.nextInt(3));
        }

        // The broken gate on the south side (the entrance)
        pillar(ctx, -3, 11, 6);
        pillar(ctx, 3, 11, 4);
        for (int dx = -2; dx <= -1; dx++) {
            ctx.set(dx, 6, 11, Blocks.CHISELED_SANDSTONE.defaultBlockState()); // the rest of the lintel has fallen
        }

        // The signs that explain the rite, on the inner faces of the cardinal pillars
        Signs.wall(ctx, 9, 2, 0, Direction.WEST, "Bring the bound", "creature of", "this altar into", "the circle.");
        Signs.wall(ctx, -9, 2, 0, Direction.EAST, "Lay a hand on", "the stone, and", "hold firm until", "it falls quiet.");
        Signs.wall(ctx, 0, 2, -9, Direction.SOUTH, "It will fight", "to break free.", "Keep it on", "its lead.");

        ctx.anchor(HALF, HALF, -1);

        return ctx.world(0, -2, 0);
    }

    private static void pillar(StructureContext ctx, int dx, int dz, int height) {
        for (int dy = 1; dy <= height; dy++) {
            ctx.set(dx, dy, dz, dy == height && height >= 4
                    ? Blocks.CHISELED_SANDSTONE.defaultBlockState()
                    : Blocks.CUT_SANDSTONE.defaultBlockState());
        }
    }
}