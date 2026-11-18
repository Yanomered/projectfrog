package com.twofrogs.clue;

import com.twofrogs.TheTwoFrogs;
import com.twofrogs.state.WorldPlanState;
import com.twofrogs.state.WorldPlanState.SiteType;
import com.twofrogs.world.structure.BookFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Writes the four clue books from the planned coordinates and the real terrain, then puts them in the containers.
 *  - Swamp ruin: the altar's X, the intermediate ruin's X and Z, how to recognize it (never the altar's Z).
 *  - Intermediate ruin: a 300 block range for the altar's Z (always including the real Z), the real surroundings,
 *    and which way the altar's gate faces.
 * A clue is generated once and then saved for good.
 */
public final class ClueGenerator {

    public static final List<SiteType> CLUE_SITES = List.of(
            SiteType.SWAMP_RUIN_A, SiteType.SWAMP_RUIN_B, SiteType.INTERMEDIATE_A, SiteType.INTERMEDIATE_B);

    private static final int Z_RANGE_WIDTH = 300;

    private ClueGenerator() {
    }

    public static void generatePending(MinecraftServer server) {
        WorldPlanState plan = WorldPlanState.get(server);
        if (!plan.isPlanned()) {
            return;
        }
        ClueState clues = ClueState.get(server);
        for (SiteType type : CLUE_SITES) {
            if (clues.isGenerated(type)) {
                continue;
            }
            try {
                if (generate(server, plan, clues, type)) {
                    TheTwoFrogs.LOGGER.info("The Two Frogs: clue written for {}.", type.name().toLowerCase());
                }
            } catch (Exception e) {
                TheTwoFrogs.LOGGER.error("The Two Frogs: could not write the clue for " + type, e);
            }
        }
    }

    public static void regenerateAll(MinecraftServer server) {
        ClueState.get(server).clear();
        generatePending(server);
    }

    private static boolean generate(MinecraftServer server, WorldPlanState plan, ClueState clues, SiteType type) {
        if (!plan.isBuilt(type)) {
            return false;
        }
        Optional<BlockPos> container = plan.getContainer(type);
        if (container.isEmpty()) {
            return false;
        }

        boolean sun = type == SiteType.SWAMP_RUIN_A || type == SiteType.INTERMEDIATE_A;
        SiteType altar = sun ? SiteType.ALTAR_A : SiteType.ALTAR_B;
        SiteType inter = sun ? SiteType.INTERMEDIATE_A : SiteType.INTERMEDIATE_B;
        Optional<BlockPos> altarPos = plan.getSite(altar);
        if (altarPos.isEmpty()) {
            return false;
        }
        ServerLevel level = server.overworld();

        List<String> pages;
        String title;
        if (type == SiteType.SWAMP_RUIN_A || type == SiteType.SWAMP_RUIN_B) {
            Optional<BlockPos> interPos = plan.getSite(inter);
            Optional<BlockPos> ruinPos = plan.getSite(type);
            if (interPos.isEmpty() || ruinPos.isEmpty()) {
                return false;
            }
            double dx = interPos.get().getX() - ruinPos.get().getX();
            double dz = interPos.get().getZ() - ruinPos.get().getZ();
            pages = ClueWriter.swampPages(sun, altarPos.get().getX(), interPos.get().getX(), interPos.get().getZ(),
                    ClueWriter.compass(dx, dz), ClueWriter.round100(Math.sqrt(dx * dx + dz * dz)),
                    ClueWriter.landmarkOf(sun));
            title = ClueWriter.swampTitle(sun);
        } else {
            if (!plan.isBuilt(altar)) {
                return false; // the clue names which way the altar's gate faces, so the altar must exist first
            }
            Optional<Direction> facing = plan.getFacing(altar);
            if (facing.isEmpty()) {
                return false;
            }

            int altarZ = altarPos.get().getZ();
            int[] range = clues.getZRange(altar);
            if (range == null || altarZ < range[0] || altarZ > range[1]) {
                Random rng = new Random(level.getSeed() ^ (altar.ordinal() * 31L) ^ 0xC10E5L);
                int offset = 40 + rng.nextInt(221); // the real Z sits somewhere inside the range, never in the middle
                int zMin = Math.floorDiv(altarZ - offset, 10) * 10;
                range = new int[]{zMin, zMin + Z_RANGE_WIDTH};
                clues.setZRange(altar, range[0], range[1]);
            }

            TerrainAnalyzer.Report report = TerrainAnalyzer.analyze(level, altarPos.get());
            pages = ClueWriter.intermediatePages(sun, range[0], range[1], report, facing.get());
            title = ClueWriter.intermediateTitle(sun);
        }

        level.getChunkAt(container.get());
        String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(container.get()).getBlock()).toString();
        clues.set(type, title, pages, blockId);
        fillContainer(level, container.get(), makeBook(clues, type));
        return true;
    }

    public static ItemStack makeBook(ClueState clues, SiteType type) {
        return BookFactory.writtenBook(clues.getTitle(type), "A Forgotten Hand", clues.getPages(type), type.name());
    }

    public static void fillContainer(ServerLevel level, BlockPos pos, ItemStack book) {
        level.getChunkAt(pos);
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof Container container) {
            container.setItem(0, book);
            entity.setChanged();
        }
    }

    /** Used after a site is rebuilt: puts the saved clue back into its new container. */
    public static void refillIfGenerated(MinecraftServer server, SiteType type) {
        ClueState clues = ClueState.get(server);
        WorldPlanState plan = WorldPlanState.get(server);
        Optional<BlockPos> container = plan.getContainer(type);
        if (clues.isGenerated(type) && container.isPresent()) {
            fillContainer(server.overworld(), container.get(), makeBook(clues, type));
        }
    }
}