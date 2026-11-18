package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Intermediate Ruin B: the "Barrow Hill". A natural-looking grassy hill with a wobbly outline and rocky
 * outcrops. A trench cuts into its slope and becomes a winding tunnel, past a half-collapsed stone doorway,
 * to a rounded burial chamber. The clue barrel sits in a sealed niche between two lore signs, and a supplies
 * chest in a side alcove. On top: uneven standing stones and a cairn with a soul lantern.
 * Nothing about the outside is rectangular: the stonework is only inside, in irregular patches.
 */
public final class IntermediateRuinB {

    /** Half the footprint, used to measure the terrain. */
    public static final int HALF_X = 9;
    public static final int HALF_Z = 11;

    private static final double RX = 8.5;
    private static final double RZ = 10.5;
    private static final double MAX_HEIGHT = 8.0;

    private IntermediateRuinB() {
    }

    // ---------- The hill ----------

    private static int hillHeight(StructureContext ctx, int dx, int dz) {
        double nx = dx / RX;
        double nz = dz / RZ;
        double r = Math.sqrt(nx * nx + nz * nz);
        double edge = 1.0 + 0.16 * ctx.noise(dx, dz, 0);   // a wobbly outline
        double t = r / edge;
        if (t >= 1.0) {
            return 0;
        }
        double h = MAX_HEIGHT * Math.pow(1.0 - t * t, 0.75) + 0.9 * ctx.noise(dx * 1.7, dz * 1.7, 1);
        return Math.max(0, (int) Math.round(h));
    }

    private static int heightAt(int[][] heights, int dx, int dz) {
        int x = dx + HALF_X;
        int z = dz + HALF_Z;
        if (x < 0 || z < 0 || x >= heights.length || z >= heights[0].length) {
            return 0;
        }
        return heights[x][z];
    }

    private static int centerX(int z, double phase) {
        return (int) Math.round(2.2 * Math.sin((z + phase) * 0.45));
    }

    private static BlockState floorBlock(StructureContext ctx) {
        double r = ctx.random.nextDouble();
        if (r < 0.55) {
            return ctx.weatheredCobble();
        }
        if (r < 0.80) {
            return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        }
        return Blocks.PACKED_MUD.defaultBlockState();
    }

    /** Builds the ruin and returns the world position of the clue barrel. */
    public static BlockPos build(StructureContext ctx) {
        double phase = ctx.random.nextDouble() * 6.28;
        ctx.prepareOval(RX - 0.5, RZ - 0.5, 18);

        // 1. The hill itself
        int[][] heights = new int[2 * HALF_X + 1][2 * HALF_Z + 1];
        for (int dx = -HALF_X; dx <= HALF_X; dx++) {
            for (int dz = -HALF_Z; dz <= HALF_Z; dz++) {
                heights[dx + HALF_X][dz + HALF_Z] = hillHeight(ctx, dx, dz);
            }
        }
        buildHill(ctx, heights);

        // 2. The trench and winding tunnel
        int tunnelEnd = 2;
        for (int z = HALF_Z; z >= tunnelEnd; z--) {
            int cx = centerX(z, phase);
            for (int dx = cx - 1; dx <= cx + 1; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    ctx.setAir(dx, dy, z);
                }
            }
        }
        for (int z = 7; z >= tunnelEnd; z--) {
            int cx = centerX(z, phase);
            for (int dx = cx - 1; dx <= cx + 1; dx++) {
                ctx.set(dx, 0, z, floorBlock(ctx));
            }
        }

        // Irregular stonework inside the tunnel, only where it is roofed by the hill
        for (int z = 6; z >= tunnelEnd; z--) {
            int cx = centerX(z, phase);
            if (heightAt(heights, cx, z) < 4) {
                continue;
            }
            for (int side : new int[]{cx - 2, cx + 2}) {
                for (int dy = 1; dy <= 3; dy++) {
                    if (!ctx.isAir(side, dy, z) && ctx.chance(0.65)) {
                        ctx.set(side, dy, z, ctx.weatheredBrick());
                    }
                }
            }
            for (int dx = cx - 1; dx <= cx + 1; dx++) {
                if (!ctx.isAir(dx, 4, z) && ctx.chance(0.5)) {
                    ctx.set(dx, 4, z, ctx.weatheredBrick());
                }
                if (!ctx.isAir(dx, 4, z) && ctx.chance(0.10)) {
                    ctx.set(dx, 3, z, Blocks.HANGING_ROOTS.defaultBlockState());
                }
            }
        }

        // The half-collapsed doorway where the tunnel first goes underground
        int doorZ = Integer.MIN_VALUE;
        for (int z = HALF_Z; z >= tunnelEnd; z--) {
            if (heightAt(heights, centerX(z, phase), z) >= 4) {
                doorZ = z;
                break;
            }
        }
        if (doorZ != Integer.MIN_VALUE) {
            int cx = centerX(doorZ, phase);
            int leftHeight = 2 + ctx.random.nextInt(2);
            int rightHeight = 2 + ctx.random.nextInt(2);
            for (int dy = 1; dy <= leftHeight; dy++) {
                ctx.set(cx - 2, dy, doorZ, ctx.weatheredBrick());
            }
            for (int dy = 1; dy <= rightHeight; dy++) {
                ctx.set(cx + 2, dy, doorZ, ctx.weatheredBrick());
            }
            for (int dx = cx - 1; dx <= cx + 1; dx++) {
                if (ctx.chance(0.85)) {
                    ctx.set(dx, 4, doorZ, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                }
            }
        }

        // 3. The burial chamber: a rounded room at the end of the tunnel
        int chamberZ = -1;
        int chamberX = centerX(tunnelEnd, phase);
        for (int dx = chamberX - 5; dx <= chamberX + 5; dx++) {
            for (int dz = chamberZ - 5; dz <= chamberZ + 5; dz++) {
                double dist2 = (dx - chamberX) * (dx - chamberX) + (dz - chamberZ) * (dz - chamberZ);
                if (dist2 <= 13.7) {
                    for (int dy = 1; dy <= 4; dy++) {
                        ctx.setAir(dx, dy, dz);
                    }
                    ctx.set(dx, 0, dz, floorBlock(ctx));
                } else if (dist2 <= 25.0) {
                    for (int dy = 1; dy <= 4; dy++) {
                        if (!ctx.isAir(dx, dy, dz) && ctx.chance(0.6)) {
                            ctx.set(dx, dy, dz, ctx.weatheredBrick());
                        }
                    }
                }
            }
        }

        // The clue niche at the far side, between two lore signs
        int nicheZ = chamberZ - 4;
        ctx.set(chamberX, 1, nicheZ, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.SOUTH));
        ctx.set(chamberX - 1, 1, nicheZ, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
        ctx.set(chamberX + 1, 1, nicheZ, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
        ctx.set(chamberX - 1, 2, nicheZ, Blocks.STONE_BRICKS.defaultBlockState());
        ctx.set(chamberX + 1, 2, nicheZ, Blocks.STONE_BRICKS.defaultBlockState());
        ctx.set(chamberX, 2, nicheZ, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
        ctx.set(chamberX, 1, nicheZ - 1, Blocks.STONE_BRICKS.defaultBlockState());
        ctx.set(chamberX, 2, nicheZ - 1, Blocks.STONE_BRICKS.defaultBlockState());
        ctx.set(chamberX, 0, nicheZ, Blocks.STONE_BRICKS.defaultBlockState());
        Signs.wall(ctx, chamberX - 1, 2, chamberZ - 3, Direction.SOUTH,
                "Here lies the", "last warden. He", "carried the", "second and fell");
        Signs.wall(ctx, chamberX + 1, 2, chamberZ - 3, Direction.SOUTH,
                "If they touch,", "all ends. Keep", "them far, keep", "them calm.");

        // The supplies alcove, on the side nearer the middle of the hill
        int side = chamberX >= 0 ? -1 : 1;
        int alcoveX = chamberX + 4 * side;
        ctx.set(alcoveX, 1, chamberZ, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, side == 1 ? Direction.WEST : Direction.EAST));
        ctx.set(alcoveX, 2, chamberZ, Blocks.STONE_BRICKS.defaultBlockState());
        ctx.set(alcoveX, 0, chamberZ, Blocks.STONE_BRICKS.defaultBlockState());
        for (int dy = 1; dy <= 2; dy++) {
            ctx.set(alcoveX + side, dy, chamberZ, Blocks.STONE_BRICKS.defaultBlockState());
            ctx.set(alcoveX, dy, chamberZ - 1, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
            ctx.set(alcoveX, dy, chamberZ + 1, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
        }
        Loot.scatter(ctx.level, ctx.world(alcoveX, 1, chamberZ), ctx.random, List.of(
                new ItemStack(Items.COOKED_PORKCHOP, 3 + ctx.random.nextInt(3)),
                new ItemStack(Items.TORCH, 6 + ctx.random.nextInt(5)),
                new ItemStack(Items.IRON_INGOT, 2 + ctx.random.nextInt(2)),
                new ItemStack(Items.ARROW, 8 + ctx.random.nextInt(5)),
                new ItemStack(Items.STRING, 3 + ctx.random.nextInt(3)),
                new ItemStack(Items.LEAD, 2)));

        // Bones, moss, roots and cobwebs inside
        for (int dx = chamberX - 4; dx <= chamberX + 4; dx++) {
            for (int dz = chamberZ - 4; dz <= chamberZ + 4; dz++) {
                if (!ctx.isAir(dx, 1, dz)) {
                    continue;
                }
                double r = ctx.random.nextDouble();
                if (r < 0.16) {
                    ctx.set(dx, 1, dz, Blocks.MOSS_CARPET.defaultBlockState());
                } else if (r < 0.21) {
                    ctx.set(dx, 1, dz, Blocks.BONE_BLOCK.defaultBlockState());
                } else if (r < 0.24) {
                    ctx.set(dx, 2, dz, Blocks.COBWEB.defaultBlockState());
                }
                if (!ctx.isAir(dx, 5, dz) && ctx.isAir(dx, 4, dz) && ctx.chance(0.12)) {
                    ctx.set(dx, 4, dz, Blocks.HANGING_ROOTS.defaultBlockState());
                }
            }
        }

        // 4. The outside: standing stones, a cairn, and boulders around the base
        standingStones(ctx, heights);
        cairn(ctx, heights);
        boulders(ctx);

        return ctx.world(chamberX, 1, nicheZ);
    }

    private static void buildHill(StructureContext ctx, int[][] heights) {
        boolean grassy = ctx.surfaceTop.is(Blocks.GRASS_BLOCK);
        for (int dx = -HALF_X; dx <= HALF_X; dx++) {
            for (int dz = -HALF_Z; dz <= HALF_Z; dz++) {
                int h = heights[dx + HALF_X][dz + HALF_Z];
                if (h <= 0) {
                    continue;
                }
                boolean rocky = ctx.noise(dx * 2.3, dz * 2.3, 1) > 0.55;
                for (int dy = 1; dy <= h; dy++) {
                    BlockState state;
                    if (dy == h) {
                        if (rocky) {
                            state = ctx.chance(0.5) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                                    : Blocks.COBBLESTONE.defaultBlockState();
                        } else if (grassy && ctx.chance(0.08)) {
                            state = Blocks.MOSS_BLOCK.defaultBlockState();
                        } else if (grassy && ctx.chance(0.08)) {
                            state = Blocks.COARSE_DIRT.defaultBlockState();
                        } else {
                            state = ctx.surfaceTop;
                        }
                    } else if (rocky && dy == h - 1) {
                        state = Blocks.COBBLESTONE.defaultBlockState();
                    } else {
                        state = ctx.surfaceFiller;
                    }
                    ctx.set(dx, dy, dz, state);
                }
                if (grassy && !rocky && ctx.chance(0.18)) {
                    ctx.set(dx, h + 1, dz, ctx.chance(0.5) ? Blocks.FERN.defaultBlockState()
                            : Blocks.GRASS.defaultBlockState());
                }
            }
        }
    }

    /** Five uneven standing stones around the top of the hill, some two blocks wide. */
    private static void standingStones(StructureContext ctx, int[][] heights) {
        double start = ctx.random.nextDouble() * Math.PI * 2.0;
        for (int i = 0; i < 5; i++) {
            double angle = start + i * (Math.PI * 2.0 / 5.0) + (ctx.random.nextDouble() - 0.5) * 0.5;
            int sx = (int) Math.round(Math.cos(angle) * RX * 0.55);
            int sz = (int) Math.round(Math.sin(angle) * RZ * 0.55);
            int base = heightAt(heights, sx, sz);
            if (base < 2) {
                continue;
            }
            stone(ctx, sx, sz, base, 3 + ctx.random.nextInt(3));
            if (ctx.chance(0.6)) {
                int nx = sx + (ctx.chance(0.5) ? 1 : 0);
                int nz = sz + (nx == sx ? 1 : 0);
                int nb = heightAt(heights, nx, nz);
                if (nb >= 1) {
                    stone(ctx, nx, nz, nb, 2 + ctx.random.nextInt(2));
                }
            }
        }
    }

    private static void stone(StructureContext ctx, int x, int z, int base, int height) {
        for (int dy = base + 1; dy <= base + height; dy++) {
            ctx.set(x, dy, z, dy == base + height ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                    : Blocks.ANDESITE.defaultBlockState());
        }
    }

    /** A rough pile of rock on the summit, topped with a soul lantern (the landmark). */
    private static void cairn(StructureContext ctx, int[][] heights) {
        int top = heightAt(heights, 0, 0);
        if (top < 2) {
            return;
        }
        int baseY = top + 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1 && ctx.chance(0.4)) {
                    continue; // ragged corners
                }
                int ground = heightAt(heights, dx, dz);
                for (int dy = ground + 1; dy <= baseY; dy++) {
                    ctx.set(dx, dy, dz, ctx.chance(0.6) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                            : Blocks.COBBLESTONE.defaultBlockState());
                }
            }
        }
        int column = 3 + ctx.random.nextInt(2);
        for (int dy = baseY + 1; dy <= baseY + column; dy++) {
            ctx.set(0, dy, 0, dy % 2 == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                    : Blocks.ANDESITE.defaultBlockState());
        }
        ctx.set(0, baseY + column + 1, 0, Blocks.SOUL_LANTERN.defaultBlockState());
    }

    /** A few rough boulders in the grass around the foot of the hill. */
    private static void boulders(StructureContext ctx) {
        for (int i = 0; i < 6; i++) {
            double angle = ctx.random.nextDouble() * Math.PI * 2.0;
            double reach = 1.05 + ctx.random.nextDouble() * 0.35;
            int bx = (int) Math.round(Math.cos(angle) * RX * reach);
            int bz = (int) Math.round(Math.sin(angle) * RZ * reach);
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    if (ctx.chance(0.2)) {
                        continue;
                    }
                    int groundY = ctx.groundTop(bx + dx, bz + dz);
                    BlockPos pos = ctx.world(bx + dx, 0, bz + dz).atY(groundY + 1);
                    if (!ctx.level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    ctx.level.setBlock(pos, ctx.chance(0.5) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                            : Blocks.COBBLESTONE.defaultBlockState(), 2);
                    if (dx == 0 && dz == 0 && ctx.chance(0.5)) {
                        ctx.level.setBlock(pos.above(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
                    }
                }
            }
        }
    }
}