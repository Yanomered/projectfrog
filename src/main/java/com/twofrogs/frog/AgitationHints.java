package com.twofrogs.frog;

import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.frog.Frog;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tells nearby players how agitated a captured or escaped special frog is, without showing numbers:
 * a message, a sound and some particles each time it crosses a threshold (and when it calms down),
 * plus occasional ambient cues from the halfway mark upward.
 */
public final class AgitationHints {

    private static final Map<FrogRole, Integer> LAST_TIER = new EnumMap<>(FrogRole.class);
    private static final Map<FrogRole, Long> NEXT_AMBIENT = new EnumMap<>(FrogRole.class);

    private AgitationHints() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(AgitationHints::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LAST_TIER.clear();
            NEXT_AMBIENT.clear();
        });
    }

    private static int tier(double agitation) {
        if (agitation >= FrogTuning.RAGE_ENTER_AGITATION) {
            return 5;
        }
        if (agitation >= FrogTuning.HINT_TIER_4) {
            return 4;
        }
        if (agitation >= FrogTuning.HINT_TIER_3) {
            return 3;
        }
        if (agitation >= FrogTuning.HINT_TIER_2) {
            return 2;
        }
        if (agitation >= FrogTuning.HINT_TIER_1) {
            return 1;
        }
        return 0;
    }

    private static void onServerTick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        TwoFrogsState state = TwoFrogsState.get(server);

        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                continue;
            }
            Frog frog = SpecialFrogManager.findFrog(server, uuid.get());
            if (frog == null) {
                continue;
            }

            boolean active = state.getBehavior(role) != FrogBehavior.DORMANT;
            int tier = active ? tier(state.getAgitation(role)) : 0;
            Integer last = LAST_TIER.put(role, tier);
            if (last == null) {
                continue; // first look after loading: do not announce anything
            }

            ServerLevel level = (ServerLevel) frog.level();
            if (tier > last) {
                announce(level, frog, tier);
            } else if (tier < last && active) {
                tell(level, frog, "The frog's breathing slows. It settles a little.", ChatFormatting.GRAY);
                sound(level, frog, SoundEvents.FROG_EAT, 0.9F, 1.0F);
            }

            if (active && tier >= 2) {
                ambient(level, frog, role, tier);
            }
        }
    }

    // ---------- Threshold messages ----------

    private static void announce(ServerLevel level, Frog frog, int tier) {
        switch (tier) {
            case 1 -> {
                tell(level, frog, "The frog fidgets restlessly.", ChatFormatting.GRAY);
                sound(level, frog, SoundEvents.FROG_AMBIENT, 0.8F, 0.9F);
            }
            case 2 -> {
                tell(level, frog, "Its throat pulses with quick, nervous croaks.", ChatFormatting.GRAY);
                sound(level, frog, SoundEvents.FROG_HURT, 0.8F, 0.8F);
                particles(level, frog, ParticleTypes.FALLING_WATER, 4);
            }
            case 3 -> {
                tell(level, frog, "Its skin is slick with sweat, and it strains against everything that holds it.",
                        ChatFormatting.YELLOW);
                sound(level, frog, SoundEvents.FROG_HURT, 1.2F, 0.6F);
                particles(level, frog, ParticleTypes.ANGRY_VILLAGER, 2);
            }
            case 4 -> {
                tell(level, frog, "A low, grating rumble builds in its throat. It is about to snap.",
                        ChatFormatting.GOLD);
                sound(level, frog, SoundEvents.WARDEN_HEARTBEAT, 1.5F, 1.2F);
                particles(level, frog, ParticleTypes.ANGRY_VILLAGER, 5);
            }
            case 5 -> tell(level, frog, "The frog's eyes turn wild!", ChatFormatting.RED);
            default -> {
            }
        }
    }

    // ---------- Occasional ambient cues ----------

    private static void ambient(ServerLevel level, Frog frog, FrogRole role, int tier) {
        long now = level.getGameTime();
        Long next = NEXT_AMBIENT.get(role);
        if (next == null) {
            NEXT_AMBIENT.put(role, now + FrogTuning.HINT_AMBIENT_MIN_TICKS);
            return;
        }
        if (now < next) {
            return;
        }
        NEXT_AMBIENT.put(role, now + FrogTuning.HINT_AMBIENT_MIN_TICKS
                + frog.getRandom().nextInt(FrogTuning.HINT_AMBIENT_EXTRA_TICKS + 1));

        sound(level, frog, SoundEvents.FROG_AMBIENT, 0.7F, 1.0F - 0.12F * tier);
        switch (tier) {
            case 2 -> particles(level, frog, ParticleTypes.FALLING_WATER, 2);
            case 3 -> particles(level, frog, ParticleTypes.ANGRY_VILLAGER, 1);
            case 4 -> particles(level, frog, ParticleTypes.ANGRY_VILLAGER, 3);
            default -> particles(level, frog, ParticleTypes.SMOKE, 4);
        }
    }

    // ---------- Helpers ----------

    private static void tell(ServerLevel level, Frog frog, String text, ChatFormatting color) {
        Component message = Component.literal(text).withStyle(color, ChatFormatting.ITALIC);
        for (ServerPlayer player : level.players()) {
            if (player.distanceTo(frog) <= FrogTuning.HINT_RADIUS_BLOCKS) {
                player.displayClientMessage(message, true);
            }
        }
    }

    private static void sound(ServerLevel level, Frog frog, SoundEvent event, float volume, float pitch) {
        level.playSound(null, frog.getX(), frog.getY(), frog.getZ(), event, SoundSource.NEUTRAL, volume, pitch);
    }

    private static void particles(ServerLevel level, Frog frog, ParticleOptions type, int count) {
        level.sendParticles(type, frog.getX(), frog.getY() + 0.5, frog.getZ(), count, 0.25, 0.25, 0.25, 0.0);
    }
}