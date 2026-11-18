package com.twofrogs.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small toolkit for building structures. Coordinates are LOCAL to the structure:
 * (0, 0, 0) is the center of the floor. Local coordinates are rotated into the world automatically,
 * and block states (stairs, barrels, vines, ladders, ...) are rotated with them.
 *
 * The terrain around a structure is blended, not cut into a rectangle: a flat area under the structure
 * fades smoothly into the natural ground over several blocks, with a wobbly outline.
 */
public final class StructureContext {

    private static final int BLEND = 7;
    private static final int MAX_CUT = 12;
    private static final int MAX_FILL = 12;

    public final ServerLevel level;
    public final BlockPos origin;      // world position of local (0, 0, 0), at floor level
    public final Rotation rotation;
    public final RandomSource random;

    /** What the natural surface looks like here (grass over dirt, sand, ...). Set by the terrain blending. */
    public BlockState surfaceTop = Blocks.GRASS_BLOCK.defaultBlockState();
    public BlockState surfaceFiller = Blocks.DIRT.defaultBlockState();

    /** The ground height (relative to the floor) of each column AFTER the terrain was blended. */
    private final Map<Long, Integer> finalGround = new HashMap<>();
    private final double[] phases = new double[6];

    private interface Shape {
        /** Distance outside the flat area (0 or less inside it). */
        double outside(int dx, int dz);
    }

    public StructureContext(ServerLevel level, BlockPos origin, Rotation rotation, RandomSource random) {
        this.level = level;
        this.origin = origin;
        this.rotation = rotation;
        this.random = random;
        for (int i = 0; i < phases.length; i++) {
            phases[i] = random.nextDouble() * Math.PI * 2.0;
        }
    }

    private static long columnKey(int dx, int dz) {
        return ((long) dx << 32) ^ (dz & 0xFFFFFFFFL);
    }

    // ---------- Coordinates ----------

    public BlockPos world(int dx, int dy, int dz) {
        return origin.offset(new BlockPos(dx, dy, dz).rotate(rotation));
    }

    public int floorY() {
        return origin.getY();
    }

    // ---------- Placing and reading ----------

    public void set(int dx, int dy, int dz, BlockState state) {
        level.setBlock(world(dx, dy, dz), state.rotate(rotation), Block.UPDATE_CLIENTS);
    }

    public void setAir(int dx, int dy, int dz) {
        level.setBlock(world(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    public BlockState get(int dx, int dy, int dz) {
        return level.getBlockState(world(dx, dy, dz));
    }

    public boolean isAir(int dx, int dy, int dz) {
        return get(dx, dy, dz).isAir();
    }

    public boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** Smooth, repeatable noise in roughly -1..1 (use channel 0 or 1 for two independent patterns). */
    public double noise(double x, double z, int channel) {
        int c = (channel & 1) * 3;
        return 0.5 * Math.sin(x * 0.31 + phases[c]) * Math.cos(z * 0.27 + phases[c + 1])
                + 0.3 * Math.sin((x + z) * 0.19 + phases[c + 2])
                + 0.2 * Math.sin((x - z) * 0.43 + phases[c + 1]);
    }

    // ---------- Terrain ----------

    /** World Y of the natural ground surface at a local column (ignores trees, plants and vines). */
    public int groundTop(int dx, int dz) {
        BlockPos p = world(dx, 0, dz);
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, p.getX(), p.getZ()) - 1;
        int minY = level.getMinBuildHeight();
        for (int i = 0; i < 48 && y > minY; i++) {
            BlockState state = level.getBlockState(new BlockPos(p.getX(), y, p.getZ()));
            boolean notGround = state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                    || state.is(Blocks.VINE) || state.canBeReplaced();
            if (!notGround) {
                break;
            }
            y--;
        }
        return y;
    }

    /** The middle value of the ground heights across the footprint: a good height for the floor. */
    public int medianGroundTop(int halfX, int halfZ) {
        List<Integer> heights = new ArrayList<>();
        for (int dx = -halfX; dx <= halfX; dx += 2) {
            for (int dz = -halfZ; dz <= halfZ; dz += 2) {
                heights.add(groundTop(dx, dz));
            }
        }
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    /**
     * Blends the terrain around a rectangular structure: flat under it (plus a one block apron), then a smooth,
     * wobbly slope back to the natural ground. Trees and plants are cleared with a ragged edge.
     */
    public void prepare(int halfX, int halfZ, int clearHeight) {
        final double hx = halfX + 1.0;
        final double hz = halfZ + 1.0;
        terraform((dx, dz) -> {
            double ex = Math.max(Math.abs(dx) - hx, 0.0);
            double ez = Math.max(Math.abs(dz) - hz, 0.0);
            return Math.sqrt(ex * ex + ez * ez); // rounded corners
        }, halfX + 1, halfZ + 1, clearHeight);
    }

    /** The same for an oval structure (a hill). */
    public void prepareOval(double radiusX, double radiusZ, int clearHeight) {
        terraform((dx, dz) -> {
            double nx = dx / radiusX;
            double nz = dz / radiusZ;
            double r = Math.sqrt(nx * nx + nz * nz);
            return Math.max(0.0, (r - 1.0) * Math.min(radiusX, radiusZ));
        }, (int) Math.ceil(radiusX), (int) Math.ceil(radiusZ), clearHeight);
    }

    private void captureSurface() {
        int g = groundTop(0, 0);
        BlockPos p = world(0, 0, 0).atY(g);
        BlockState top = level.getBlockState(p);
        BlockState filler = level.getBlockState(p.below());
        if (!top.isAir() && level.getFluidState(p).isEmpty()) {
            surfaceTop = top;
        }
        if (!filler.isAir() && !filler.canBeReplaced()) {
            surfaceFiller = filler;
        }
    }

    private void terraform(Shape shape, int extentX, int extentZ, int clearHeight) {
        captureSurface();
        int rangeX = extentX + BLEND + 4;
        int rangeZ = extentZ + BLEND + 4;

        for (int dx = -rangeX; dx <= rangeX; dx++) {
            for (int dz = -rangeZ; dz <= rangeZ; dz++) {
                double outside = shape.outside(dx, dz);
                // Flat inside the shape; outside it the blend distance wobbles with noise, so the edge is organic.
                double d = outside <= 0.0 ? 0.0 : Math.max(0.0, outside + 2.6 * noise(dx, dz, 0));
                if (d >= BLEND + 1.0) {
                    continue; // too far away to be touched
                }
                double t = Mth.clamp(d / BLEND, 0.0, 1.0);
                double w = 1.0 - t * t * (3.0 - 2.0 * t); // 1 on the flat area, fading smoothly to 0

                int ground = groundTop(dx, dz);
                int rel = ground - floorY();
                int target = Mth.clamp((int) Math.round(rel * (1.0 - w)), rel - MAX_CUT, rel + MAX_FILL);

                if (target != rel) {
                    BlockPos wp = world(dx, 0, dz);
                    BlockState top = level.getBlockState(new BlockPos(wp.getX(), ground, wp.getZ()));
                    BlockState filler = level.getBlockState(new BlockPos(wp.getX(), ground - 1, wp.getZ()));
                    if (top.isAir() || !top.getFluidState().isEmpty()) {
                        top = surfaceTop;
                    }
                    if (filler.isAir() || filler.canBeReplaced() || !filler.getFluidState().isEmpty()) {
                        filler = surfaceFiller;
                    }
                    if (target > rel) {
                        for (int dy = rel + 1; dy <= target; dy++) {
                            set(dx, dy, dz, dy == target ? top : filler);
                        }
                    } else {
                        for (int dy = target + 1; dy <= rel; dy++) {
                            setAir(dx, dy, dz);
                        }
                        set(dx, target, dz, top);
                    }
                }
                finalGround.put(columnKey(dx, dz), target);

                // Clear trees and plants: fully on the flat area, only a little in the blend zone.
                int clear = d <= 1.5 ? clearHeight : (w > 0.25 ? 3 : 0);
                for (int dy = target + 1; dy <= target + clear; dy++) {
                    BlockPos p = world(dx, dy, dz);
                    if (!level.getBlockState(p).isAir() && level.getFluidState(p).isEmpty()) {
                        setAir(dx, dy, dz);
                    }
                }

                // Disturbed ground and a little rubble across the transition.
                if (w > 0.1 && w < 0.85) {
                    double r = random.nextDouble();
                    if (r < 0.04 && isAir(dx, target + 1, dz)) {
                        set(dx, target + 1, dz, chance(0.5) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                                : Blocks.COBBLESTONE.defaultBlockState());
                    } else if (r < 0.12 && surfaceTop.is(Blocks.GRASS_BLOCK)) {
                        set(dx, target, dz, Blocks.COARSE_DIRT.defaultBlockState());
                    }
                }
            }
        }
    }

    /**
     * Makes sure nothing hangs in the air: under the lowest solid block of every column (floor,
     * walls, and any piece sticking out past the edge) the gap down to the ground is filled.
     * Columns within 'skipRadius' of the center are left alone (use that for cellars); pass -1 to skip none.
     * Call this after the solid parts are built and before vines and decoration.
     */
    public void anchor(int halfX, int halfZ, int skipRadius) {
        for (int dx = -halfX - 1; dx <= halfX + 1; dx++) {
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                if (Math.abs(dx) <= skipRadius && Math.abs(dz) <= skipRadius) {
                    continue;
                }
                Integer ground = finalGround.get(columnKey(dx, dz));
                if (ground == null) {
                    continue;
                }
                int lowest = Integer.MIN_VALUE;
                for (int dy = 0; dy <= 4; dy++) {
                    BlockPos p = world(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (!s.getCollisionShape(level, p).isEmpty()) {
                        lowest = dy;
                        break;
                    }
                }
                if (lowest == Integer.MIN_VALUE) {
                    continue;
                }
                for (int dy = lowest - 1; dy > ground && dy >= -14; dy--) {
                    BlockState s = get(dx, dy, dz);
                    if (s.isAir() || s.canBeReplaced()) {
                        set(dx, dy, dz, chance(0.5) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                                : Blocks.COBBLESTONE.defaultBlockState());
                    }
                }
            }
        }
    }

    // ---------- Palettes ----------

    /** A weathered stone brick block: mostly plain, some mossy, some cracked, a few fallen to cobblestone. */
    public BlockState weatheredBrick() {
        double r = random.nextDouble();
        if (r < 0.50) {
            return Blocks.STONE_BRICKS.defaultBlockState();
        }
        if (r < 0.78) {
            return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        }
        if (r < 0.92) {
            return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        }
        return Blocks.COBBLESTONE.defaultBlockState();
    }

    /** A weathered cobblestone block. */
    public BlockState weatheredCobble() {
        double r = random.nextDouble();
        if (r < 0.45) {
            return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        }
        if (r < 0.85) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }
        return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
    }
}