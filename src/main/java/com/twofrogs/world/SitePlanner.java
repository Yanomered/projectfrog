package com.twofrogs.world;

import com.mojang.datafixers.util.Pair;
import com.twofrogs.TheTwoFrogs;
import com.twofrogs.state.WorldPlanState;
import com.twofrogs.state.WorldPlanState.SiteType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Random;

/**
 * Decides where every structure goes, using the real terrain generator (no chunks are generated
 * while planning). Distance rules are never relaxed: if they cannot be met, planning fails and says why.
 * Site positions are stored as the top ground block at the site's center.
 */
public class SitePlanner {

    public record PlanResult(boolean success, String message) {
    }

    // Distance rules
    public static final double MIN_HABITAT_TO_ALTAR = 3000.0;
    public static final double MIN_ALTAR_TO_ALTAR = 8000.0;
    private static final double MIN_INTERMEDIATE_TO_HABITAT = 1000.0;
    private static final double MIN_INTERMEDIATE_TO_ALTAR = 1000.0;
    private static final double MIN_INTERMEDIATE_TO_INTERMEDIATE = 2000.0;
    private static final double MIN_RUIN_SEPARATION = 90.0;

    // Habitat rules
    private static final int MIN_HABITAT_FROM_SPAWN = 400;
    private static final int MAX_HABITAT_FROM_SPAWN = 3000;
    private static final int HABITAT_SEARCH_RADIUS = 3200;
    private static final int HABITAT_ATTEMPTS = 12;

    // Site search
    private static final int ALTAR_ATTEMPTS = 80;
    private static final int ALTAR_HALF_SIZE = 12;
    private static final int ALTAR_MAX_RELIEF = 10;
    private static final int INTERMEDIATE_HALF_SIZE = 9;
    private static final int INTERMEDIATE_MAX_RELIEF = 8;

    private record Habitat(BlockPos center, BlockPos ruinA, BlockPos ruinB) {
    }

    private record Destinations(BlockPos intermediateA, BlockPos intermediateB,
                                BlockPos altarA, BlockPos altarB) {
    }

    // ---------- Entry point ----------

    public static PlanResult plan(MinecraftServer server) {
        ServerLevel level = server.overworld();
        WorldPlanState plan = WorldPlanState.get(server);
        if (plan.isPlanned()) {
            return new PlanResult(false, "The world is already planned. Use /twofrogs plan reset to plan again.");
        }

        TheTwoFrogs.LOGGER.info("The Two Frogs: planning structure sites. This can take a little while...");
        Random rng = new Random(level.getSeed() ^ 0x2F20F06EL);

        Habitat habitat = chooseHabitat(level, rng);
        if (habitat == null) {
            return new PlanResult(false,
                    "Could not find a suitable Swamp habitat (needs a Swamp biome 400 to 3000 blocks from spawn). "
                            + "Is this an Overworld with swamps? Superflat worlds have none.");
        }

        Destinations destinations = chooseDestinations(level, habitat.center(), rng);
        if (destinations == null) {
            return new PlanResult(false,
                    "Found a habitat, but could not place both routes while keeping the required distances "
                            + "(3000 habitat to altar, 8000 altar to altar). Nothing was relaxed. See the log for details.");
        }

        plan.setSite(SiteType.HABITAT, habitat.center());
        plan.setSite(SiteType.SWAMP_RUIN_A, habitat.ruinA());
        plan.setSite(SiteType.SWAMP_RUIN_B, habitat.ruinB());
        plan.setSite(SiteType.INTERMEDIATE_A, destinations.intermediateA());
        plan.setSite(SiteType.INTERMEDIATE_B, destinations.intermediateB());
        plan.setSite(SiteType.ALTAR_A, destinations.altarA());
        plan.setSite(SiteType.ALTAR_B, destinations.altarB());
        plan.setPlanned(true);

        TheTwoFrogs.LOGGER.info("The Two Frogs: planning complete.");
        return new PlanResult(true, "Planning complete. Use /twofrogs sites to see the results.");
    }

    // ---------- Habitat ----------

    private static Habitat chooseHabitat(ServerLevel level, Random rng) {
        BlockPos spawn = level.getSharedSpawnPos();

        for (int attempt = 0; attempt < HABITAT_ATTEMPTS; attempt++) {
            BlockPos origin = attempt == 0
                    ? spawn
                    : ring(spawn, 600 + attempt * 250, rng.nextDouble() * Math.PI * 2.0);

            Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(
                    holder -> holder.is(Biomes.SWAMP), origin, HABITAT_SEARCH_RADIUS, 32, 64);
            if (found == null) {
                continue;
            }

            BlockPos center = refineSwampCenter(level, found.getFirst());
            if (center == null) {
                continue;
            }
            double distanceFromSpawn = horizontalDistance(center, spawn);
            if (distanceFromSpawn < MIN_HABITAT_FROM_SPAWN || distanceFromSpawn > MAX_HABITAT_FROM_SPAWN) {
                continue;
            }

            BlockPos ruinA = findRuinSite(level, center, rng, null);
            if (ruinA == null) {
                continue;
            }
            BlockPos ruinB = findRuinSite(level, center, rng, ruinA);
            if (ruinB == null) {
                continue;
            }
            TheTwoFrogs.LOGGER.info("The Two Frogs: habitat chosen after {} attempt(s).", attempt + 1);
            return new Habitat(center, ruinA, ruinB);
        }
        return null;
    }

    private static BlockPos refineSwampCenter(ServerLevel level, BlockPos hit) {
        int cx = hit.getX();
        int cz = hit.getZ();

        // Move the center toward the middle of the surrounding swamp.
        for (int iteration = 0; iteration < 3; iteration++) {
            long sumX = 0;
            long sumZ = 0;
            int count = 0;
            for (int dx = -64; dx <= 64; dx += 8) {
                for (int dz = -64; dz <= 64; dz += 8) {
                    if (isSwamp(level, cx + dx, cz + dz)) {
                        sumX += cx + dx;
                        sumZ += cz + dz;
                        count++;
                    }
                }
            }
            if (count == 0) {
                return null;
            }
            cx = (int) (sumX / count);
            cz = (int) (sumZ / count);
        }

        // At least 70% of a 48 block radius must be swamp.
        int swamp = 0;
        int total = 0;
        for (int dx = -48; dx <= 48; dx += 8) {
            for (int dz = -48; dz <= 48; dz += 8) {
                if (dx * dx + dz * dz <= 48 * 48) {
                    total++;
                    if (isSwamp(level, cx + dx, cz + dz)) {
                        swamp++;
                    }
                }
            }
        }
        if (swamp < total * 0.7) {
            return null;
        }
        return new BlockPos(cx, groundY(level, cx, cz), cz);
    }

    /** A dry, fairly flat spot inside the swamp, at least MIN_RUIN_SEPARATION from 'other'. */
    private static BlockPos findRuinSite(ServerLevel level, BlockPos center, Random rng, BlockPos other) {
        for (int tries = 0; tries < 300; tries++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double radius = 10 + rng.nextInt(90);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);

            if (other != null && horizontalDistance(other, new BlockPos(x, 0, z)) < MIN_RUIN_SEPARATION) {
                continue;
            }
            if (!isSwamp(level, x, z)) {
                continue;
            }
            int[] terrain = sampleTerrain(level, x, z, 4);
            if (terrain == null || terrain[0] > 3) {
                continue;
            }
            return new BlockPos(x, terrain[1] - 1, z);
        }
        return null;
    }

    // ---------- Altars and intermediate ruins ----------

    private static Destinations chooseDestinations(ServerLevel level, BlockPos center, Random rng) {
        int noSite = 0;
        int badDistance = 0;
        int sameBiome = 0;
        int noIntermediate = 0;

        for (int attempt = 0; attempt < ALTAR_ATTEMPTS; attempt++) {
            double thetaA = rng.nextDouble() * Math.PI * 2.0;
            double thetaB = thetaA + Math.PI + (rng.nextDouble() - 0.5) * Math.toRadians(60.0);
            BlockPos idealA = ring(center, 3500 + rng.nextInt(1700), thetaA);
            BlockPos idealB = ring(center, 3500 + rng.nextInt(1700), thetaB);

            // Cheap early check before doing expensive terrain searches (allows for search drift).
            if (horizontalDistance(idealA, idealB) < MIN_ALTAR_TO_ALTAR + 600.0) {
                badDistance++;
                continue;
            }

            BlockPos altarA = bestSite(level, idealA, 256, 48, ALTAR_HALF_SIZE, ALTAR_MAX_RELIEF);
            BlockPos altarB = altarA == null ? null
                    : bestSite(level, idealB, 256, 48, ALTAR_HALF_SIZE, ALTAR_MAX_RELIEF);
            if (altarA == null || altarB == null) {
                noSite++;
                continue;
            }

            if (horizontalDistance(center, altarA) < MIN_HABITAT_TO_ALTAR
                    || horizontalDistance(center, altarB) < MIN_HABITAT_TO_ALTAR
                    || horizontalDistance(altarA, altarB) < MIN_ALTAR_TO_ALTAR) {
                badDistance++;
                continue;
            }

            if (biomeKey(level, altarA).equals(biomeKey(level, altarB))) {
                sameBiome++;
                continue;
            }

            BlockPos interA = chooseIntermediate(level, center, altarA, rng);
            BlockPos interB = interA == null ? null : chooseIntermediate(level, center, altarB, rng);
            if (interA == null || interB == null
                    || horizontalDistance(interA, interB) < MIN_INTERMEDIATE_TO_INTERMEDIATE) {
                noIntermediate++;
                continue;
            }

            TheTwoFrogs.LOGGER.info("The Two Frogs: altar sites chosen after {} attempt(s).", attempt + 1);
            return new Destinations(interA, interB, altarA, altarB);
        }

        TheTwoFrogs.LOGGER.warn("The Two Frogs: could not place the altars. Rejections: no suitable terrain {}, "
                        + "distance rules {}, same biome {}, no intermediate site {}.",
                noSite, badDistance, sameBiome, noIntermediate);
        return null;
    }

    private static BlockPos chooseIntermediate(ServerLevel level, BlockPos center, BlockPos altar, Random rng) {
        double dx = altar.getX() - center.getX();
        double dz = altar.getZ() - center.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);

        for (int tries = 0; tries < 6; tries++) {
            double t = 0.35 + rng.nextDouble() * 0.30;
            double side = rng.nextBoolean() ? 1.0 : -1.0;
            double lateral = (200 + rng.nextInt(300)) * side;
            int x = (int) Math.round(center.getX() + dx * t + (-dz / length) * lateral);
            int z = (int) Math.round(center.getZ() + dz * t + (dx / length) * lateral);

            BlockPos site = bestSite(level, new BlockPos(x, 0, z), 192, 48,
                    INTERMEDIATE_HALF_SIZE, INTERMEDIATE_MAX_RELIEF);
            if (site == null) {
                continue;
            }
            if (horizontalDistance(site, center) >= MIN_INTERMEDIATE_TO_HABITAT
                    && horizontalDistance(site, altar) >= MIN_INTERMEDIATE_TO_ALTAR) {
                return site;
            }
        }
        return null;
    }

    /** Searches a square around 'ideal' for the flattest dry spot in an acceptable biome. */
    private static BlockPos bestSite(ServerLevel level, BlockPos ideal, int radius, int step,
                                     int halfSize, int maxRelief) {
        BlockPos best = null;
        int bestRelief = Integer.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = ideal.getX() + dx;
                int z = ideal.getZ() + dz;

                int[] terrain = sampleTerrain(level, x, z, halfSize);
                if (terrain == null || terrain[0] > maxRelief) {
                    continue;
                }
                if (!acceptableBiome(level.getBiome(new BlockPos(x, terrain[1], z)))) {
                    continue;
                }
                double distance = (double) dx * dx + (double) dz * dz;
                if (terrain[0] < bestRelief || (terrain[0] == bestRelief && distance < bestDistance)) {
                    best = new BlockPos(x, terrain[1] - 1, z);
                    bestRelief = terrain[0];
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    // ---------- Terrain sampling (uses the generator directly, no chunk loading) ----------

    /**
     * Samples a grid around (x, z). Returns {relief, centerHeight} if the whole area is dry land above
     * sea level, otherwise null. centerHeight is the first free Y above the ground.
     */
    private static int[] sampleTerrain(ServerLevel level, int x, int z, int halfSize) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int sea = level.getSeaLevel();

        int centerFloor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
        int centerSurface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        if (centerSurface != centerFloor || centerFloor <= sea + 1) {
            return null;
        }

        int min = centerFloor;
        int max = centerFloor;
        int stride = Math.max(2, halfSize / 2);
        for (int ox = -halfSize; ox <= halfSize; ox += stride) {
            for (int oz = -halfSize; oz <= halfSize; oz += stride) {
                int floor = generator.getBaseHeight(x + ox, z + oz, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                int surface = generator.getBaseHeight(x + ox, z + oz, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                if (surface != floor || floor <= sea) {
                    return null; // water in the footprint
                }
                min = Math.min(min, floor);
                max = Math.max(max, floor);
            }
        }
        return new int[]{max - min, centerFloor};
    }

    private static int groundY(ServerLevel level, int x, int z) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState) - 1;
    }

    // ---------- Helpers ----------

    private static boolean isSwamp(ServerLevel level, int x, int z) {
        return level.getBiome(new BlockPos(x, level.getSeaLevel(), z)).is(Biomes.SWAMP);
    }

    private static boolean acceptableBiome(Holder<Biome> biome) {
        return !biome.is(BiomeTags.IS_OCEAN)
                && !biome.is(BiomeTags.IS_RIVER)
                && !biome.is(BiomeTags.IS_BEACH)
                && !biome.is(Biomes.SWAMP)
                && !biome.is(Biomes.MANGROVE_SWAMP)
                && !biome.is(Biomes.MUSHROOM_FIELDS);
    }

    private static String biomeKey(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
    }

    private static BlockPos ring(BlockPos center, int radius, double angle) {
        return new BlockPos(
                center.getX() + (int) Math.round(Math.cos(angle) * radius),
                0,
                center.getZ() + (int) Math.round(Math.sin(angle) * radius));
    }

    public static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}