package com.twofrogs.frog;

import com.twofrogs.TheTwoFrogs;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogLocation;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * Core logic for the two special frogs:
 *  - immortality (Stage 2)
 *  - location tracking and void rescue (Stage 2)
 *  - behavior states DORMANT / CAPTURED / ESCAPED, cumulative agitation, glowing (Stage 3)
 *  - leash / boat / minecart escape, via ContainmentEscape (Stage 4)
 */
public class SpecialFrogManager {

    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int VOID_MARGIN_BLOCKS = 8;

    private static final UUID SPEED_MODIFIER_ID = UUID.fromString("5e7b2f0a-3c1d-4a6e-9b8f-1d2c3e4f5a6b");
    private static final String SPEED_MODIFIER_NAME = "the_two_frogs_escape_speed";

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(SpecialFrogManager::allowDamage);
        ServerTickEvents.END_SERVER_TICK.register(SpecialFrogManager::onServerTick);
    }

    /** Return false to cancel the damage. */
    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof Frog) || entity.level().isClientSide) {
            return true;
        }
        MinecraftServer server = entity.getServer();
        if (server == null) {
            return true;
        }
        return !TwoFrogsState.get(server).isSpecial(entity.getUUID());
    }

    private static void onServerTick(MinecraftServer server) {
        fastBehaviorCheck(server);            // notices a freed frog the very tick it happens
        EscapedBehavior.onServerTick(server); // runs every tick
        PartnerSeeker.onServerTick(server);   // runs every tick

        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        TwoFrogsState state = TwoFrogsState.get(server);
        long now = server.overworld().getGameTime();

        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                continue;
            }
            Frog frog = findFrog(server, uuid.get());

            if (frog != null) {
                ServerLevel level = (ServerLevel) frog.level();
                String dimension = level.dimension().location().toString();
                FrogLocation here = new FrogLocation(dimension, frog.blockPosition());
                state.setLastKnown(role, here);

                if (frog.getY() < level.getMinBuildHeight() - VOID_MARGIN_BLOCKS) {
                    rescueFromVoid(level, frog, role, state.getSafe(role), dimension);
                } else if (isSafeSpot(level, frog)) {
                    state.setSafe(role, here);
                }

                // Leash / boat / minecart escape attempts happen before the state check,
                // so a frog that breaks free is marked ESCAPED in the same second.
                ContainmentEscape.tick(state, role, frog);

                updateBehavior(state, role, frog, now);
            }

            // Agitation keeps building even when the frog's chunk is unloaded.
            accrueAgitation(state, role, now);

            if (frog != null) {
                applyBehaviorEffects(state, role, frog);
            }
        }
    }

    /**
     * Every tick: notices the moment a frog is freed (lead removed, climbed out of a boat, ...) so its glow,
     * sound and first move happen immediately instead of up to a second later.
     */
    private static void fastBehaviorCheck(MinecraftServer server) {
        TwoFrogsState state = TwoFrogsState.get(server);
        long now = server.overworld().getGameTime();

        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                continue;
            }
            FrogBehavior before = state.getBehavior(role);
            if (before == FrogBehavior.DORMANT) {
                continue; // the slower per-second check handles the first capture
            }
            Frog frog = findFrog(server, uuid.get());
            if (frog == null) {
                continue;
            }
            updateBehavior(state, role, frog, now);
            if (state.getBehavior(role) != before) {
                applyBehaviorEffects(state, role, frog); // glow and the rest, right now
            }
        }
    }

    public static Frog findFrog(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof Frog frog && frog.isAlive()) {
                return frog;
            }
        }
        return null;
    }

    // ---------- Behavior state machine ----------

    private static void updateBehavior(TwoFrogsState state, FrogRole role, Frog frog, long now) {
        FrogBehavior current = state.getBehavior(role);
        boolean restrained = frog.isLeashed() || frog.isPassenger();
        FrogBehavior next = current;

        switch (current) {
            case DORMANT -> {
                if (restrained) {
                    next = FrogBehavior.CAPTURED;
                } else if (state.hasHabitat() && !state.isInHabitat(state.getLastKnown(role))) {
                    next = FrogBehavior.ESCAPED;
                }
            }
            case CAPTURED -> {
                // A lead whose holder has not been restored yet (right after a chunk reload)
                // still counts as restrained, so a reload never causes a fake escape.
                if (!restrained && !hasPendingLeash(frog)) {
                    next = FrogBehavior.ESCAPED;
                }
            }
            case ESCAPED -> {
                if (restrained) {
                    next = FrogBehavior.CAPTURED;
                }
            }
        }

        if (next == current) {
            return;
        }

        state.setBehavior(role, next);
        if (state.getLastAgitationTick(role) < 0) {
            state.setLastAgitationTick(role, now); // agitation clock starts at first removal
        }

        if (next == FrogBehavior.ESCAPED) {
            state.incrementEscapes(role);
            FrogSounds.escapeCue(frog);
            EscapedBehavior.startle(frog);
            addAgitation(state, role, FrogTuning.ESCAPE_BONUS);
        } else if (current == FrogBehavior.ESCAPED && next == FrogBehavior.CAPTURED) {
            state.incrementRecaptures(role);
            addAgitation(state, role, FrogTuning.RECAPTURE_BONUS);
        }

        if (current == FrogBehavior.ESCAPED) {
            frog.removeEffect(MobEffects.GLOWING);
        }

        TheTwoFrogs.LOGGER.info("Frog {}: {} -> {} (agitation {}, escapes {}, recaptures {})",
                role, current, next, String.format("%.2f", state.getAgitation(role)),
                state.getEscapeCount(role), state.getRecaptureCount(role));
    }

    /** True if the frog's saved data still contains a lead that is waiting for its holder. */
    private static boolean hasPendingLeash(Frog frog) {
        CompoundTag saved = new CompoundTag();
        frog.saveWithoutId(saved);
        return saved.contains("Leash");
    }

    // ---------- Agitation ----------

    private static void addAgitation(TwoFrogsState state, FrogRole role, double amount) {
        state.setAgitation(role, Math.min(FrogTuning.MAX_AGITATION, state.getAgitation(role) + amount));
    }

    private static void accrueAgitation(TwoFrogsState state, FrogRole role, long now) {
        if (state.getBehavior(role) == FrogBehavior.DORMANT || state.getBehavior(role) == FrogBehavior.SEALED) {
            return;
        }
        long last = state.getLastAgitationTick(role);
        if (last < 0) {
            state.setLastAgitationTick(role, now);
            return;
        }
        long elapsed = now - last;
        if (elapsed <= 0) {
            return;
        }
        state.setLastAgitationTick(role, now);
        state.addAwayTicks(role, elapsed);

        double seconds = elapsed / 20.0;
        if (state.isInHabitat(state.getLastKnown(role))) {
            // Back home in the swamp: the frog slowly calms down.
            double calmed = state.getAgitation(role) - FrogTuning.HOME_CALM_RATE_PER_SECOND * seconds;
            state.setAgitation(role, Math.max(0.0, calmed));
            return;
        }
        double rate = FrogTuning.BASE_RATE_PER_SECOND
                + FrogTuning.DISTANCE_RATE_PER_SECOND * distanceFactor(state, role);
        addAgitation(state, role, rate * seconds);
    }

    /** 0.0 = right next to the partner (or unknown), 1.0 = very far away or in another dimension. */
    private static double distanceFactor(TwoFrogsState state, FrogRole role) {
        FrogRole partner = role == FrogRole.A ? FrogRole.B : FrogRole.A;
        FrogLocation mine = state.getLastKnown(role);
        FrogLocation theirs = state.getLastKnown(partner);
        if (mine == null || theirs == null) {
            return 0.0;
        }
        if (!mine.dimension().equals(theirs.dimension())) {
            return 1.0;
        }
        double dx = mine.pos().getX() - theirs.pos().getX();
        double dz = mine.pos().getZ() - theirs.pos().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        return Mth.clamp(distance / FrogTuning.DISTANCE_FOR_MAX_BLOCKS, 0.0, 1.0);
    }

    // ---------- Visible and physical effects of each state ----------

    private static void applyBehaviorEffects(TwoFrogsState state, FrogRole role, Frog frog) {
        switch (state.getBehavior(role)) {
            case DORMANT -> {
                removeSpeedBonus(frog);
                // Keep the frog wandering inside its habitat, exactly like normal vanilla wandering.
                if (state.hasHabitat() && !frog.hasRestriction()) {
                    frog.restrictTo(state.getHabitatCenter().get(), state.getHabitatRadius());
                }
            }
            case CAPTURED -> {
                removeSpeedBonus(frog);
                frog.clearRestriction();
            }
            case ESCAPED -> {
                frog.clearRestriction();
                double level = Mth.clamp(state.getAgitation(role) / FrogTuning.MAX_AGITATION, 0.0, 1.0);
                setSpeedBonus(frog, Mth.lerp(level, FrogTuning.SPEED_BONUS_AT_MIN, FrogTuning.SPEED_BONUS_AT_MAX));
                // The ONLY intentional visual sign: the vanilla Glowing outline (no particles, no icon).
                frog.addEffect(new MobEffectInstance(MobEffects.GLOWING, FrogTuning.GLOW_DURATION_TICKS,
                        0, false, false, false));
            }
        }
    }

    private static void setSpeedBonus(Frog frog, double amount) {
        AttributeInstance speed = frog.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(SPEED_MODIFIER_ID);
        speed.addTransientModifier(new AttributeModifier(SPEED_MODIFIER_ID, SPEED_MODIFIER_NAME,
                amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static void removeSpeedBonus(Frog frog) {
        AttributeInstance speed = frog.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SPEED_MODIFIER_ID);
        }
    }

    // ---------- Safe spot and void rescue (Stage 2) ----------

    private static boolean isSafeSpot(ServerLevel level, Frog frog) {
        BlockPos feet = frog.blockPosition();
        BlockPos below = feet.below();
        boolean solidBelow = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
        boolean noFluid = level.getFluidState(feet).isEmpty();
        return solidBelow && noFluid && !frog.isOnFire();
    }

    private static void rescueFromVoid(ServerLevel level, Frog frog, FrogRole role,
                                       FrogLocation safe, String currentDimension) {
        if (safe == null || !safe.dimension().equals(currentDimension)) {
            TheTwoFrogs.LOGGER.warn("Frog {} is below the world in {} and has no safe spot in this dimension.",
                    role, currentDimension);
            return;
        }
        BlockPos target = safe.pos();
        level.getChunkAt(target); // make sure the destination chunk is loaded

        frog.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                frog.getYRot(), frog.getXRot());
        frog.setDeltaMovement(Vec3.ZERO);
        frog.fallDistance = 0.0F;
        frog.getNavigation().stop();

        TheTwoFrogs.LOGGER.info("Frog {} fell out of the world and was returned to {}.", role, safe);
    }
}