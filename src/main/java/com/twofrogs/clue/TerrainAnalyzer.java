package com.twofrogs.clue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Looks at the real terrain around a place (using the world generator, so it describes the natural land, not
 * the structure) and finds the features that genuinely exist near it. The clue texts only ever mention
 * features found here. Directions: north is -Z, east is +X.
 */
public final class TerrainAnalyzer {

    /** A feature: which way it lies, how far (blocks) and a size (cliff drop, or how much higher a peak is). */
    public record Feature(String direction, int distance, int size) {
    }

    public record Report(String biome, int centerHeight, Feature river, Feature cliff, Feature peak,
                         Feature lake, Feature sea, boolean basin, boolean highGround, String woodland) {
    }

    private static final int[] RADII = {28, 42, 56, 70, 84, 98, 112};
    private static final int ANGLES = 16;
    private static final int NEAR_RINGS = 4;

    private TerrainAnalyzer() {
    }

    private static String direction(int angleIndex) {
        double angle = angleIndex * (Math.PI * 2.0 / ANGLES);
        return ClueWriter.compass(Math.sin(angle), -Math.cos(angle));
    }

    public static Report analyze(ServerLevel level, BlockPos center) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int cx = center.getX();
        int cz = center.getZ();
        int centerHeight = generator.getBaseHeight(cx, cz, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);

        int n = RADII.length;
        int[][] height = new int[n][ANGLES];
        boolean[][] water = new boolean[n][ANGLES];
        boolean[][] river = new boolean[n][ANGLES];
        boolean[][] ocean = new boolean[n][ANGLES];
        int forest = 0;
        int taiga = 0;
        int jungle = 0;

        for (int i = 0; i < n; i++) {
            for (int a = 0; a < ANGLES; a++) {
                double angle = a * (Math.PI * 2.0 / ANGLES);
                int x = cx + (int) Math.round(Math.sin(angle) * RADII[i]);
                int z = cz - (int) Math.round(Math.cos(angle) * RADII[i]);

                int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                height[i][a] = floor;
                water[i][a] = surface > floor;

                Holder<Biome> biome = level.getBiome(new BlockPos(x, Math.max(floor, level.getSeaLevel()), z));
                river[i][a] = biome.is(BiomeTags.IS_RIVER);
                ocean[i][a] = biome.is(BiomeTags.IS_OCEAN);
                if (biome.is(BiomeTags.IS_FOREST)) {
                    forest++;
                } else if (biome.is(BiomeTags.IS_TAIGA)) {
                    taiga++;
                } else if (biome.is(BiomeTags.IS_JUNGLE)) {
                    jungle++;
                }
            }
        }

        // The nearest river, lake and sea
        Feature riverFeature = null;
        Feature lakeFeature = null;
        Feature seaFeature = null;
        for (int i = 0; i < n && (riverFeature == null || lakeFeature == null || seaFeature == null); i++) {
            for (int a = 0; a < ANGLES; a++) {
                if (riverFeature == null && river[i][a]) {
                    riverFeature = new Feature(direction(a), RADII[i], 0);
                }
                if (seaFeature == null && ocean[i][a]) {
                    seaFeature = new Feature(direction(a), RADII[i], 0);
                }
                if (lakeFeature == null && water[i][a] && !river[i][a] && !ocean[i][a]) {
                    lakeFeature = new Feature(direction(a), RADII[i], 0);
                }
            }
        }

        // The nearest cliff: a sharp change in height between two neighboring rings on dry land
        Feature cliffFeature = null;
        for (int i = 0; i < n - 1; i++) {
            for (int a = 0; a < ANGLES; a++) {
                if (water[i][a] || water[i + 1][a]) {
                    continue;
                }
                int drop = Math.abs(height[i + 1][a] - height[i][a]);
                int distance = (RADII[i] + RADII[i + 1]) / 2;
                if (drop >= 12 && (cliffFeature == null || distance < cliffFeature.distance())) {
                    cliffFeature = new Feature(direction(a), distance, drop);
                }
            }
        }

        // The highest ground, if it is much higher than the altar
        Feature peakFeature = null;
        int bestRise = 27;
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < ANGLES; a++) {
                int rise = height[i][a] - centerHeight;
                if (!water[i][a] && rise > bestRise) {
                    bestRise = rise;
                    peakFeature = new Feature(direction(a), RADII[i], rise);
                }
            }
        }

        // A hollow or a rise: how much of the nearby land is clearly higher or lower
        int higher = 0;
        int lower = 0;
        for (int i = 0; i < NEAR_RINGS; i++) {
            for (int a = 0; a < ANGLES; a++) {
                if (height[i][a] >= centerHeight + 14) {
                    higher++;
                }
                if (height[i][a] <= centerHeight - 14) {
                    lower++;
                }
            }
        }
        int nearTotal = NEAR_RINGS * ANGLES;
        boolean basin = higher >= nearTotal * 0.55;
        boolean highGround = !basin && lower >= nearTotal * 0.55;

        // Woodland
        String woodland = null;
        int total = n * ANGLES;
        if (forest + taiga + jungle >= total * 0.45) {
            if (forest >= taiga && forest >= jungle) {
                woodland = "dense forest";
            } else if (taiga >= jungle) {
                woodland = "dark pine woods";
            } else {
                woodland = "tangled jungle";
            }
        }

        String biomeName = level.getBiome(new BlockPos(cx, centerHeight, cz)).unwrapKey()
                .map(k -> k.location().getPath().replace('_', ' ')).orElse("wild");

        return new Report(biomeName, centerHeight, riverFeature, cliffFeature, peakFeature, lakeFeature,
                seaFeature, basin, highGround, woodland);
    }
}