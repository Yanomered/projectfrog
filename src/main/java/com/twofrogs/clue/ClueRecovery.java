package com.twofrogs.clue;

import com.twofrogs.TheTwoFrogs;
import com.twofrogs.state.WorldPlanState;
import com.twofrogs.state.WorldPlanState.SiteType;
import com.twofrogs.world.structure.BookFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * "The stones remember": if a clue's container is destroyed or its book is gone (burned, lost in lava, a
 * death drop that despawned...), then after a player has stayed near the site for five minutes the container is
 * restored and the book returns. Nothing is restored while any copy exists in an online player's inventory or
 * ender chest, in the container, or lying on the ground nearby.
 */
public final class ClueRecovery {

    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final long GRACE_TICKS = 6000L; // five minutes
    private static final double NEAR_RADIUS_SQ = 32.0 * 32.0;

    private static final Map<SiteType, Long> MISSING_SINCE = new EnumMap<>(SiteType.class);

    private ClueRecovery() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % CHECK_INTERVAL_TICKS == 0) {
                run(server, false);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MISSING_SINCE.clear());
    }

    /** Debug: do the check right now, without the five minute wait. Returns how many books were restored. */
    public static int runNow(MinecraftServer server) {
        return run(server, true);
    }

    private static boolean isClue(ItemStack stack, SiteType type) {
        if (!stack.is(Items.WRITTEN_BOOK) || !stack.hasTag()) {
            return false;
        }
        return type.name().equals(stack.getTag().getString(BookFactory.CLUE_TAG));
    }

    private static boolean containerHas(Container container, SiteType type) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (isClue(container.getItem(i), type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean playerHas(MinecraftServer server, SiteType type) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (containerHas(player.getInventory(), type) || containerHas(player.getEnderChestInventory(), type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean droppedNear(ServerLevel level, BlockPos pos, SiteType type) {
        return !level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(48.0),
                entity -> isClue(entity.getItem(), type)).isEmpty();
    }

    private static BlockState restoreState(String blockId) {
        Block block = blockId.isEmpty() ? Blocks.BARREL : BuiltInRegistries.BLOCK.get(new ResourceLocation(blockId));
        if (block == Blocks.CHEST) {
            return Blocks.CHEST.defaultBlockState();
        }
        return Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
    }

    private static int run(MinecraftServer server, boolean immediate) {
        ClueState clues = ClueState.get(server);
        WorldPlanState plan = WorldPlanState.get(server);
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        int restored = 0;

        for (SiteType type : ClueGenerator.CLUE_SITES) {
            if (!clues.isGenerated(type)) {
                continue;
            }
            Optional<BlockPos> posOpt = plan.getContainer(type);
            if (posOpt.isEmpty() || !level.hasChunkAt(posOpt.get())) {
                continue;
            }
            BlockPos pos = posOpt.get();
            Vec3 center = Vec3.atCenterOf(pos);

            boolean near = false;
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(center) <= NEAR_RADIUS_SQ) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                MISSING_SINCE.remove(type);
                continue;
            }

            // The container itself may be gone.
            if (!(level.getBlockEntity(pos) instanceof Container)) {
                level.setBlock(pos, restoreState(clues.getBlockId(type)), 3);
            }
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof Container container)) {
                continue;
            }

            boolean present = containerHas(container, type) || playerHas(server, type) || droppedNear(level, pos, type);
            if (present) {
                MISSING_SINCE.remove(type);
                continue;
            }
            long since = MISSING_SINCE.computeIfAbsent(type, k -> now);
            if (!immediate && now - since < GRACE_TICKS) {
                continue;
            }

            // Put a copy back in the first free slot.
            ItemStack book = ClueGenerator.makeBook(clues, type);
            boolean placed = false;
            for (int i = 0; i < container.getContainerSize() && !placed; i++) {
                if (container.getItem(i).isEmpty()) {
                    container.setItem(i, book);
                    placed = true;
                }
            }
            if (!placed) {
                continue;
            }
            entity.setChanged();
            MISSING_SINCE.remove(type);
            clues.incrementRecoveries();
            restored++;

            level.sendParticles(ParticleTypes.ENCHANT, center.x, center.y + 1.0, center.z, 30, 0.6, 0.6, 0.6, 0.8);
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.5F, 0.8F);
            Component message = Component.literal("The old stones return what was lost.")
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC);
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(center) <= NEAR_RADIUS_SQ) {
                    player.displayClientMessage(message, true);
                }
            }
            TheTwoFrogs.LOGGER.info("The Two Frogs: the clue for {} was restored.", type.name().toLowerCase());
        }
        return restored;
    }
}