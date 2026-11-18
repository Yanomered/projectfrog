package com.twofrogs.frog;

import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Feeding a slimeball to a captured or escaped special frog calms it down.
 * Dormant frogs and ordinary frogs are untouched: they react exactly like vanilla.
 */
public final class SoothingHandler {

    private static final Map<UUID, Long> LAST_FED = new HashMap<>();

    private SoothingHandler() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide || !(world instanceof ServerLevel level) || !(entity instanceof Frog frog)) {
                return InteractionResult.PASS;
            }
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(Items.SLIME_BALL)) {
                return InteractionResult.PASS;
            }

            TwoFrogsState state = TwoFrogsState.get(level.getServer());
            Optional<FrogRole> role = state.getRole(frog.getUUID());
            if (role.isEmpty() || state.getBehavior(role.get()) == FrogBehavior.DORMANT) {
                return InteractionResult.PASS; // vanilla behavior
            }

            double current = state.getAgitation(role.get());
            long now = level.getGameTime();
            Long last = LAST_FED.get(frog.getUUID());
            if (current <= 0.5 || (last != null && now - last < FrogTuning.SOOTHE_COOLDOWN_TICKS)) {
                return InteractionResult.SUCCESS; // already calm, or still chewing: nothing is used up
            }

            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            state.setAgitation(role.get(), Math.max(0.0, current - FrogTuning.SOOTHE_AMOUNT));
            LAST_FED.put(frog.getUUID(), now);

            level.sendParticles(ParticleTypes.HEART, frog.getX(), frog.getY() + 0.5, frog.getZ(),
                    4, 0.2, 0.2, 0.2, 0.02);
            level.playSound(null, frog.getX(), frog.getY(), frog.getZ(),
                    SoundEvents.FROG_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
            return InteractionResult.SUCCESS;
        });
    }
}