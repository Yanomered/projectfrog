package com.twofrogs.encounter;

import com.twofrogs.frog.FrogTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Finds fair places for encounters to appear, and picks mobs that suit the land. */
public final class EncounterSpawner {

    public enum Kind {
        PATROL, AMBUSH, WATER
    }

    private EncounterSpawner() {
    }

    public static boolean isNight(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return level.isThundering() || (t >= 13000L && t <= 23000L);
    }

    public static boolean isCave(ServerLevel level, BlockPos pos) {
        return !level.canSeeSky(pos) && pos.getY() < level.getSeaLevel() - 3;
    }

    /**
     * Looks for a spot between minDist and maxDist from the player: valid ground (or water), dark enough
     * when asked, and never close to any player. Patrols tend to appear ahead of where the player is looking.
     */
    public static Optional<BlockPos> findSpawn(ServerLevel level, ServerPlayer owner, Kind kind,
                                               double minDist, double maxDist, boolean needDark, boolean relaxed) {
        RandomSource random = owner.getRandom();
        Vec3 look = owner.getLookAngle();
        double heading = Math.atan2(look.z, look.x);

        for (int tries = 0; tries < 48; tries++) {
            double angle = (kind == Kind.PATROL && random.nextDouble() < 0.6)
                    ? heading + (random.nextDouble() - 0.5) * Math.toRadians(140.0)
                    : random.nextDouble() * Math.PI * 2.0;
            double distance = minDist + random.nextDouble() * (maxDist - minDist);
            int x = Mth.floor(owner.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(owner.getZ() + Math.sin(angle) * distance);

            Optional<BlockPos> spot = columnSpot(level, owner, kind, x, z, needDark, relaxed);
            if (spot.isPresent() && farFromPlayers(level, spot.get())) {
                return spot;
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> columnSpot(ServerLevel level, ServerPlayer owner, Kind kind,
                                                 int x, int z, boolean needDark, boolean relaxed) {
        int baseY = owner.blockPosition().getY();
        for (int dy = 8; dy >= -12; dy--) {
            BlockPos pos = new BlockPos(x, baseY + dy, z);
            if (!level.hasChunkAt(pos)) {
                return Optional.empty();
            }

            if (kind == Kind.WATER) {
                if (level.getFluidState(pos).is(FluidTags.WATER) && level.getFluidState(pos.below()).is(FluidTags.WATER)) {
                    return Optional.of(pos);
                }
                continue;
            }

            if (!FrogTerrain.solid(level, pos.below()) || FrogTerrain.solid(level, pos)
                    || FrogTerrain.solid(level, pos.above())
                    || !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) {
                continue;
            }
            if (!relaxed) {
                if (kind == Kind.AMBUSH && level.canSeeSky(pos)) {
                    continue; // ambushes come out of the dark, not the open
                }
                if (needDark && level.getBrightness(LightLayer.BLOCK, pos) > 7) {
                    continue;
                }
            }
            return Optional.of(pos);
        }
        return Optional.empty();
    }

    public static boolean farFromPlayers(ServerLevel level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double limit = EncounterTuning.PLAYER_CLEARANCE * EncounterTuning.PLAYER_CLEARANCE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) < limit) {
                return false;
            }
        }
        return true;
    }

    /** Mobs that suit the land around 'pos'. */
    public static List<EntityType<?>> pick(ServerLevel level, BlockPos pos, Kind kind, int count, boolean cave) {
        List<EntityType<?>> pool = new ArrayList<>();
        Holder<Biome> biome = level.getBiome(pos);

        if (kind == Kind.WATER) {
            pool.add(EntityType.DROWNED);
        } else if (cave) {
            pool.add(EntityType.SKELETON);
            pool.add(EntityType.ZOMBIE);
            pool.add(EntityType.CAVE_SPIDER);
            pool.add(EntityType.SPIDER);
        } else if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) {
            pool.add(EntityType.HUSK);
            pool.add(EntityType.HUSK);
            pool.add(EntityType.SKELETON);
            pool.add(EntityType.SPIDER);
        } else if (biome.value().coldEnoughToSnow(pos)) {
            pool.add(EntityType.STRAY);
            pool.add(EntityType.STRAY);
            pool.add(EntityType.ZOMBIE);
            pool.add(EntityType.SKELETON);
        } else if (biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA)) {
            pool.add(EntityType.SPIDER);
            pool.add(EntityType.SKELETON);
            pool.add(EntityType.ZOMBIE);
            pool.add(EntityType.SPIDER);
        } else if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) {
            pool.add(EntityType.ZOMBIE);
            pool.add(EntityType.SKELETON);
            pool.add(EntityType.WITCH);
            pool.add(EntityType.ZOMBIE);
        } else {
            pool.add(EntityType.ZOMBIE);
            pool.add(EntityType.SKELETON);
            pool.add(EntityType.SPIDER);
            pool.add(EntityType.ZOMBIE);
        }

        RandomSource random = level.getRandom();
        List<EntityType<?>> chosen = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (kind != Kind.WATER && !cave && random.nextDouble() < 0.08) {
                chosen.add(EntityType.CREEPER); // the odd creeper
            } else {
                chosen.add(pool.get(random.nextInt(pool.size())));
            }
        }
        return chosen;
    }

    /** The sound a mob makes, used as the warning before it appears. */
    public static SoundEvent ambient(EntityType<?> type) {
        if (type == EntityType.HUSK) {
            return SoundEvents.HUSK_AMBIENT;
        }
        if (type == EntityType.SKELETON) {
            return SoundEvents.SKELETON_AMBIENT;
        }
        if (type == EntityType.STRAY) {
            return SoundEvents.STRAY_AMBIENT;
        }
        if (type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER) {
            return SoundEvents.SPIDER_AMBIENT;
        }
        if (type == EntityType.DROWNED) {
            return SoundEvents.DROWNED_AMBIENT;
        }
        if (type == EntityType.WITCH) {
            return SoundEvents.WITCH_AMBIENT;
        }
        if (type == EntityType.CREEPER) {
            return SoundEvents.CREEPER_PRIMED;
        }
        return SoundEvents.ZOMBIE_AMBIENT;
    }
}