package com.twofrogs.world.structure;

import com.twofrogs.TheTwoFrogs;
import com.twofrogs.state.WorldPlanState;
import com.twofrogs.state.WorldPlanState.SiteType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Builds planned structures into the world, once each. Loads (and generates) the terrain around a site
 * first, measures it, builds, then records the result so it is never built twice.
 */
public final class StructureBuilder {

    private StructureBuilder() {
    }

    public static boolean isSupported(SiteType type) {
        return type != SiteType.HABITAT;
    }

    private static boolean isAltar(SiteType type) {
        return type == SiteType.ALTAR_A || type == SiteType.ALTAR_B;
    }

    /** Half the footprint (x, z) of a structure, used to measure the terrain. */
    private static int[] halfSize(SiteType type) {
        return switch (type) {
            case SWAMP_RUIN_A -> new int[]{SwampRuinA.HALF_X, SwampRuinA.HALF_Z};
            case SWAMP_RUIN_B -> new int[]{SwampRuinB.HALF, SwampRuinB.HALF};
            case INTERMEDIATE_A -> new int[]{IntermediateRuinA.HALF, IntermediateRuinA.HALF};
            case INTERMEDIATE_B -> new int[]{IntermediateRuinB.HALF_X, IntermediateRuinB.HALF_Z};
            case ALTAR_A -> new int[]{AltarA.HALF, AltarA.HALF};
            case ALTAR_B -> new int[]{AltarB.HALF, AltarB.HALF};
            default -> null;
        };
    }

    /** Builds every supported site that has not been built yet. Safe to call as often as you like. */
    public static void buildPending(MinecraftServer server) {
        WorldPlanState plan = WorldPlanState.get(server);
        if (!plan.isPlanned()) {
            return;
        }
        for (SiteType type : SiteType.values()) {
            if (isSupported(type) && !plan.isBuilt(type) && plan.getSite(type).isPresent()) {
                TheTwoFrogs.LOGGER.info("The Two Frogs: {}", build(server, type, false));
            }
        }
    }

    public static String build(MinecraftServer server, SiteType type, boolean force) {
        WorldPlanState plan = WorldPlanState.get(server);
        Optional<BlockPos> site = plan.getSite(type);
        if (site.isEmpty()) {
            return type.name().toLowerCase() + " is not planned yet.";
        }
        if (!isSupported(type)) {
            return type.name().toLowerCase() + " cannot be built.";
        }
        if (plan.isBuilt(type) && !force) {
            return type.name().toLowerCase() + " is already built.";
        }

        ServerLevel level = server.overworld();
        BlockPos pos = site.get();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        // Load (and generate) the area, and keep it loaded while we build.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(chunkX + dx, chunkZ + dz, true);
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }

        try {
            RandomSource random = RandomSource.create(level.getSeed() ^ pos.asLong() ^ (type.ordinal() * 7919L));
            Rotation rotation = Rotation.values()[random.nextInt(4)];

            int[] half = halfSize(type);

            // Measure the real terrain to choose the floor height.
            StructureContext probe = new StructureContext(level, new BlockPos(pos.getX(), 0, pos.getZ()), rotation, random);
            int floorY = probe.medianGroundTop(half[0], half[1]);

            StructureContext ctx = new StructureContext(level, new BlockPos(pos.getX(), floorY, pos.getZ()), rotation, random);
            BlockPos container = switch (type) {
                case SWAMP_RUIN_A -> SwampRuinA.build(ctx);
                case SWAMP_RUIN_B -> SwampRuinB.build(ctx);
                case INTERMEDIATE_A -> IntermediateRuinA.build(ctx);
                case INTERMEDIATE_B -> IntermediateRuinB.build(ctx);
                case ALTAR_A -> AltarA.build(ctx);
                default -> AltarB.build(ctx);
            };

            if (!isAltar(type)) {
                fillPlaceholderClue(level, container, type);
            }
            plan.setContainer(type, container);
            plan.setFacing(type, rotation.rotate(Direction.SOUTH)); // the way the entrance faces in the world
            plan.markBuilt(type);
            com.twofrogs.clue.ClueGenerator.refillIfGenerated(server, type);
            return type.name().toLowerCase() + " built (floor at y=" + floorY + ").";
        } finally {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setChunkForced(chunkX + dx, chunkZ + dz, false);
                }
            }
        }
    }

    /** Stage 7 replaces this with the real clue. For now it is an obvious placeholder. */
    private static void fillPlaceholderClue(ServerLevel level, BlockPos containerPos, SiteType type) {
        BlockEntity entity = level.getBlockEntity(containerPos);
        if (entity instanceof Container container) {
            container.setItem(0, BookFactory.writtenBook(
                    "Weathered Fragment", "Unknown",
                    List.of("The ink has run and the words are lost.\n\n(Placeholder: the real clue for "
                            + type.name().toLowerCase().replace('_', ' ') + " arrives in Stage 7.)")));
            if (type == SiteType.SWAMP_RUIN_A || type == SiteType.SWAMP_RUIN_B) {
                // A small cache of slimeballs, so players have a first way to calm a frog.
                container.setItem(1, new ItemStack(Items.SLIME_BALL, 3));
            }
            entity.setChanged();
        } else {
            TheTwoFrogs.LOGGER.warn("The Two Frogs: no container found at {} for {}", containerPos, type);
        }
    }
}