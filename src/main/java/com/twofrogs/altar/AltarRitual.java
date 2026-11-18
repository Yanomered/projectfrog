package com.twofrogs.altar;

import com.twofrogs.TheTwoFrogs;
import com.twofrogs.frog.SpecialFrogManager;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import com.twofrogs.state.WorldPlanState;
import com.twofrogs.state.WorldPlanState.SiteType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The altars' sealing rite.
 *  - Lead the right frog into the circle, right-click the pedestal, and hold firm for five seconds.
 *  - On success the frog is sealed for good: frozen on the pedestal, caged in iron bars, marked SEALED.
 *  - When both frogs are sealed, the Binding is complete (victory).
 * Also: a faint hum and sparkles near each altar, and the cage is kept intact.
 */
public final class AltarRitual {

    public static final String SEALED_TAG = "twofrogs_sealed";

    private static final List<SiteType> ALTARS = List.of(SiteType.ALTAR_A, SiteType.ALTAR_B);
    private static final double CIRCLE_RADIUS = 3.8;
    private static final int RITUAL_TICKS = 100;
    private static final double LEASH_TEAR_CHANCE_PER_SECOND = 0.04;
    private static final double PLAYER_MAX_DISTANCE_SQ = 100.0;
    private static final double AMBIENT_RADIUS_SQ = 256.0;

    private static final Map<SiteType, Ritual> ACTIVE = new EnumMap<>(SiteType.class);

    private static final class Ritual {
        final UUID player;
        int ticksLeft = RITUAL_TICKS;

        Ritual(UUID player) {
            this.player = player;
        }
    }

    private AltarRitual() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(AltarRitual::onUseBlock);
        UseEntityCallback.EVENT.register(AltarRitual::onUseEntity);
        ServerTickEvents.END_SERVER_TICK.register(AltarRitual::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE.clear());
    }

    private static FrogRole roleOf(SiteType type) {
        return type == SiteType.ALTAR_A ? FrogRole.A : FrogRole.B;
    }

    private static SiteType altarOf(FrogRole role) {
        return role == FrogRole.A ? SiteType.ALTAR_A : SiteType.ALTAR_B;
    }

    public static Frog findFrog(MinecraftServer server, FrogRole role) {
        Optional<UUID> id = TwoFrogsState.get(server).getFrogUuid(role);
        return id.isEmpty() ? null : SpecialFrogManager.findFrog(server, id.get());
    }

    // ---------- Interaction ----------

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return InteractionResult.PASS;
        }
        WorldPlanState plan = WorldPlanState.get(server);
        for (SiteType type : ALTARS) {
            Optional<BlockPos> pedestal = plan.getContainer(type);
            if (pedestal.isPresent() && pedestal.get().equals(hit.getBlockPos())) {
                interact(server, serverPlayer, plan, type, pedestal.get());
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    /** A sealed frog cannot be leashed again. */
    private static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity,
                                                 net.minecraft.world.phys.EntityHitResult hit) {
        if (entity instanceof Frog frog && frog.getTags().contains(SEALED_TAG)
                && player.getItemInHand(hand).is(Items.LEAD)) {
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    private static boolean inCircle(Frog frog, BlockPos pedestal) {
        double dx = frog.getX() - (pedestal.getX() + 0.5);
        double dz = frog.getZ() - (pedestal.getZ() + 0.5);
        return dx * dx + dz * dz <= CIRCLE_RADIUS * CIRCLE_RADIUS
                && Math.abs(frog.getY() - (pedestal.getY() + 1.0)) <= 3.0;
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting color) {
        player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }

    private static void interact(MinecraftServer server, ServerPlayer player, WorldPlanState plan,
                                 SiteType type, BlockPos pedestal) {
        ServerLevel level = server.overworld();
        FrogRole role = roleOf(type);
        FrogRole other = role == FrogRole.A ? FrogRole.B : FrogRole.A;

        if (plan.isActivated(type)) {
            tell(player, "The stone is silent. What it holds, it holds for good.", ChatFormatting.GRAY);
            return;
        }
        if (ACTIVE.containsKey(type)) {
            tell(player, "The rite is already under way.", ChatFormatting.GRAY);
            return;
        }

        Frog own = findFrog(server, role);
        Frog otherFrog = findFrog(server, other);
        boolean ownHere = own != null && own.level() == level && inCircle(own, pedestal);
        if (!ownHere) {
            boolean otherHere = otherFrog != null && otherFrog.level() == level && inCircle(otherFrog, pedestal);
            if (otherHere) {
                tell(player, "The stone does not recognize this creature. It belongs to another altar.",
                        ChatFormatting.RED);
            } else {
                tell(player, "The stone is cold. It waits for the creature it was made to hold.",
                        ChatFormatting.GRAY);
            }
            return;
        }
        if (!own.isLeashed()) {
            tell(player, "Bind it to a lead first, or it will bolt the moment the stone wakes.", ChatFormatting.YELLOW);
            return;
        }

        ACTIVE.put(type, new Ritual(player.getUUID()));
        level.playSound(null, pedestal, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 0.7F);
        tell(player, "The stone wakes. Hold firm. Keep the creature in the circle and on its lead.",
                ChatFormatting.AQUA);
    }

    // ---------- The rite ----------

    private static void onServerTick(MinecraftServer server) {
        if (!ACTIVE.isEmpty()) {
            tickRituals(server);
        }
        int tick = server.getTickCount();
        if (tick % 20 == 0) {
            ambience(server, tick);
        }
        if (tick % 40 == 0) {
            keepCages(server);
        }
    }

    private static void tickRituals(MinecraftServer server) {
        WorldPlanState plan = WorldPlanState.get(server);
        ServerLevel level = server.overworld();

        for (SiteType type : new ArrayList<>(ACTIVE.keySet())) {
            Ritual ritual = ACTIVE.get(type);
            Optional<BlockPos> pedestalOpt = plan.getContainer(type);
            ServerPlayer player = server.getPlayerList().getPlayer(ritual.player);
            Frog frog = findFrog(server, roleOf(type));

            if (pedestalOpt.isEmpty()) {
                abort(type, level, null, null, "The rite fails.");
                continue;
            }
            BlockPos pedestal = pedestalOpt.get();
            if (player == null || !player.isAlive() || player.level() != level
                    || player.distanceToSqr(Vec3.atCenterOf(pedestal)) > PLAYER_MAX_DISTANCE_SQ) {
                abort(type, level, pedestal, player, "The rite is broken: you stepped away from the stone.");
                continue;
            }
            if (frog == null || frog.level() != level || !frog.isLeashed() || !inCircle(frog, pedestal)) {
                abort(type, level, pedestal, player, "The creature left the circle, or slipped its lead. The rite fails.");
                continue;
            }

            ritual.ticksLeft--;

            if (ritual.ticksLeft % 3 == 0) {
                level.sendParticles(ParticleTypes.ENCHANT, pedestal.getX() + 0.5, pedestal.getY() + 1.5,
                        pedestal.getZ() + 0.5, 8, 1.3, 0.9, 1.3, 0.6);
                level.sendParticles(ParticleTypes.PORTAL, frog.getX(), frog.getY() + 0.3, frog.getZ(),
                        3, 0.2, 0.2, 0.2, 0.3);
            }

            if (ritual.ticksLeft > 0 && ritual.ticksLeft % 20 == 0) {
                int seconds = ritual.ticksLeft / 20;
                tell(player, "The stone hums... hold firm (" + seconds + ")", ChatFormatting.AQUA);
                level.playSound(null, pedestal, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS,
                        1.0F, 0.6F + 0.1F * (5 - seconds));
                if (frog.getRandom().nextDouble() < LEASH_TEAR_CHANCE_PER_SECOND) {
                    frog.dropLeash(true, true);
                    abort(type, level, pedestal, player, "The creature tears free of its lead! The rite fails.");
                    continue;
                }
            }

            if (ritual.ticksLeft <= 0) {
                ACTIVE.remove(type);
                seal(server, type, frog);
            }
        }
    }

    private static void abort(SiteType type, ServerLevel level, BlockPos pedestal, ServerPlayer player, String message) {
        ACTIVE.remove(type);
        if (player != null) {
            tell(player, message, ChatFormatting.RED);
        }
        if (pedestal != null) {
            level.playSound(null, pedestal, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0F, 0.8F);
        }
    }

    // ---------- Sealing ----------

    /** Seals a frog at its altar. Used by the rite and by the debug command. */
    public static String forceSeal(MinecraftServer server, FrogRole role) {
        SiteType type = altarOf(role);
        WorldPlanState plan = WorldPlanState.get(server);
        if (plan.getContainer(type).isEmpty()) {
            return "That altar is not built yet.";
        }
        if (plan.isActivated(type)) {
            return "Frog " + role + " is already sealed.";
        }
        Frog frog = findFrog(server, role);
        if (frog == null) {
            return "Frog " + role + " is not loaded. Use /twofrogs debug bring " + role.name().toLowerCase() + " first.";
        }
        if (frog.level() != server.overworld()) {
            return "Frog " + role + " is not in the Overworld.";
        }
        seal(server, type, frog);
        return "Frog " + role + " is sealed at its altar.";
    }

    private static void seal(MinecraftServer server, SiteType type, Frog frog) {
        WorldPlanState plan = WorldPlanState.get(server);
        TwoFrogsState state = TwoFrogsState.get(server);
        ServerLevel level = server.overworld();
        FrogRole role = roleOf(type);
        BlockPos pedestal = plan.getContainer(type).orElseThrow();

        level.getChunkAt(pedestal); // make sure the altar is loaded

        if (frog.isPassenger()) {
            frog.stopRiding();
        }
        if (frog.isLeashed()) {
            frog.dropLeash(true, true);
        }

        state.setBehavior(role, FrogBehavior.SEALED);
        state.setAgitation(role, 0.0);
        plan.markActivated(type);

        // Frozen on the pedestal for good
        frog.removeEffect(MobEffects.GLOWING);
        frog.moveTo(pedestal.getX() + 0.5, pedestal.getY() + 1.0, pedestal.getZ() + 0.5, frog.getYRot(), 0.0F);
        frog.setDeltaMovement(Vec3.ZERO);
        frog.getNavigation().stop();
        frog.setNoAi(true);
        frog.setNoGravity(true);
        frog.setInvulnerable(true);
        frog.setPersistenceRequired();
        frog.addTag(SEALED_TAG);

        ensureCage(level, pedestal);

        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, frog.getX(), frog.getY() + 0.5, frog.getZ(),
                60, 0.6, 0.8, 0.6, 0.4);
        level.playSound(null, pedestal, SoundEvents.TOTEM_USE, SoundSource.BLOCKS, 1.5F, 0.8F);
        level.playSound(null, pedestal, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.5F, 0.6F);

        Component message = Component.literal("The stone falls quiet. The creature is bound.")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(Vec3.atCenterOf(pedestal)) <= 2500.0) {
                player.sendSystemMessage(message);
                player.displayClientMessage(message, true);
            }
        }
        TheTwoFrogs.LOGGER.info("Frog {} has been sealed at {}.", role, type);

        if (plan.isActivated(SiteType.ALTAR_A) && plan.isActivated(SiteType.ALTAR_B)) {
            plan.setBindingComplete(true);
            victory(server);
        } else {
            Component reminder = Component.literal("One keeper is bound. Its partner is still out there. "
                    + "Do not let them meet.").withStyle(ChatFormatting.YELLOW);
            for (ServerPlayer player : level.players()) {
                player.sendSystemMessage(reminder);
            }
        }
    }

    private static void victory(MinecraftServer server) {
        Component title = Component.literal("THE BINDING IS COMPLETE")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        Component subtitle = Component.literal("Both creatures are sealed. The world is safe.")
                .withStyle(ChatFormatting.YELLOW);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 120, 30));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
        }
        TheTwoFrogs.LOGGER.info("The Binding is complete: both frogs are sealed.");
    }

    // ---------- The cage ----------

    /** Fills the iron bar cage around a sealed frog. Cells with someone standing in them wait until they are free. */
    private static void ensureCage(ServerLevel level, BlockPos pedestal) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 1; dy <= 3; dy++) {
                    boolean center = dx == 0 && dz == 0;
                    if (center && dy != 3) {
                        continue; // the frog's own cell and the air above it stay free
                    }
                    BlockPos pos = pedestal.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    if (!level.getEntities((Entity) null, new AABB(pos)).isEmpty()) {
                        continue;
                    }
                    level.setBlock(pos, Blocks.IRON_BARS.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void keepCages(MinecraftServer server) {
        WorldPlanState plan = WorldPlanState.get(server);
        ServerLevel level = server.overworld();
        for (SiteType type : ALTARS) {
            Optional<BlockPos> pedestal = plan.getContainer(type);
            if (pedestal.isPresent() && plan.isActivated(type) && level.hasChunkAt(pedestal.get())) {
                ensureCage(level, pedestal.get());
            }
        }
    }

    // ---------- Atmosphere ----------

    private static void ambience(MinecraftServer server, int tick) {
        WorldPlanState plan = WorldPlanState.get(server);
        ServerLevel level = server.overworld();

        for (SiteType type : ALTARS) {
            Optional<BlockPos> pedestalOpt = plan.getContainer(type);
            if (pedestalOpt.isEmpty() || !level.hasChunkAt(pedestalOpt.get())) {
                continue;
            }
            BlockPos pedestal = pedestalOpt.get();
            Vec3 center = Vec3.atCenterOf(pedestal);

            boolean anyoneNear = false;
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(center) <= AMBIENT_RADIUS_SQ) {
                    anyoneNear = true;
                    break;
                }
            }
            if (!anyoneNear) {
                continue;
            }

            if (plan.isActivated(type)) {
                level.sendParticles(ParticleTypes.GLOW, center.x, center.y + 1.0, center.z, 3, 0.5, 0.5, 0.5, 0.02);
            } else {
                level.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 1.2, center.z, 2, 0.4, 0.6, 0.4, 0.01);
                if (tick % 100 == 0) {
                    level.playSound(null, pedestal, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 0.5F, 0.5F);
                }
            }
        }
    }
}