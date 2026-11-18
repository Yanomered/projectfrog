package com.twofrogs.world;

import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import com.twofrogs.state.WorldPlanState;
import com.twofrogs.state.WorldPlanState.SiteType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Spawns the two special frogs among ordinary ones in the planned swamp habitat. */
public class HabitatSpawner {

    public static final int HABITAT_RADIUS = 64;
    private static final int SPECIAL_SPAWN_RADIUS = 28;
    private static final int DECOY_COUNT = 10;
    private static final int DECOY_RADIUS = 36;

    public static String spawnFrogs(MinecraftServer server) {
        TwoFrogsState state = TwoFrogsState.get(server);
        WorldPlanState plan = WorldPlanState.get(server);

        if (state.getFrogUuid(FrogRole.A).isPresent() || state.getFrogUuid(FrogRole.B).isPresent()) {
            return "Frogs are already assigned. Use /twofrogs debug clear first if you want to start over.";
        }
        Optional<BlockPos> habitat = plan.getSite(SiteType.HABITAT);
        if (habitat.isEmpty()) {
            return "No habitat is planned yet. Run /twofrogs plan first.";
        }

        ServerLevel level = server.overworld();
        BlockPos center = habitat.get();
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;

        // Load (and if needed generate) the area, and keep it loaded while we place frogs.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setChunkForced(chunkX + dx, chunkZ + dz, true);
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }

        try {
            Random rng = new Random(level.getSeed() ^ 0x5EEDL);
            List<BlockPos> used = new ArrayList<>();

            BlockPos posA = randomLand(level, center, SPECIAL_SPAWN_RADIUS, rng, used, 12.0);
            if (posA == null) {
                return "Could not find dry land for the special frogs in the habitat.";
            }
            used.add(posA);
            BlockPos posB = randomLand(level, center, SPECIAL_SPAWN_RADIUS, rng, used, 12.0);
            if (posB == null) {
                return "Could not find dry land for the second special frog in the habitat.";
            }
            used.add(posB);

            Frog a = spawnFrog(level, posA, true);
            Frog b = spawnFrog(level, posB, true);
            if (a == null || b == null) {
                return "Could not create the special frogs.";
            }

            int decoys = 0;
            for (int i = 0; i < DECOY_COUNT; i++) {
                BlockPos p = randomLand(level, center, DECOY_RADIUS, rng, used, 3.0);
                if (p == null) {
                    continue;
                }
                used.add(p);
                if (spawnFrog(level, p, false) != null) {
                    decoys++;
                }
            }

            state.setFrogUuid(FrogRole.A, a.getUUID());
            state.setFrogUuid(FrogRole.B, b.getUUID());
            state.setHabitatCenter(center);
            state.setHabitatRadius(HABITAT_RADIUS);
            plan.setFrogsSpawned(true);

            return "Habitat created: the two special frogs and " + decoys + " ordinary frogs were spawned.";
        } finally {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.setChunkForced(chunkX + dx, chunkZ + dz, false);
                }
            }
        }
    }

    private static Frog spawnFrog(ServerLevel level, BlockPos pos, boolean persistent) {
        Frog frog = EntityType.FROG.create(level);
        if (frog == null) {
            return null;
        }
        frog.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        frog.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null, null);
        if (persistent) {
            frog.setPersistenceRequired();
        }
        level.addFreshEntity(frog);
        return frog;
    }

    /** A dry spot on solid ground, not too close to anything already used. */
    private static BlockPos randomLand(ServerLevel level, BlockPos center, int radius, Random rng,
                                       List<BlockPos> used, double minSeparation) {
        for (int tries = 0; tries < 150; tries++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double r = rng.nextDouble() * radius;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * r);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * r);

            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            BlockPos below = pos.below();

            if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(below).isEmpty()) {
                continue;
            }
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                continue;
            }
            boolean tooClose = false;
            for (BlockPos other : used) {
                double dx = other.getX() - x;
                double dz = other.getZ() - z;
                if (Math.sqrt(dx * dx + dz * dz) < minSeparation) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                return pos;
            }
        }
        return null;
    }
}