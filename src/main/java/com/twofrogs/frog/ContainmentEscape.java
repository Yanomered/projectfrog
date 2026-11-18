package com.twofrogs.frog;

import com.twofrogs.TheTwoFrogs;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogLocation;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 4: lets a captured frog work its way free of a vanilla leash, a boat or a minecart.
 * Called once per second for every loaded special frog.
 *
 * The timers below are kept in memory only. Agitation (the main driver) is saved in the world.
 */
public class ContainmentEscape {

    private static final Map<UUID, Integer> LEASHED_SECONDS = new HashMap<>();
    private static final Map<UUID, Vec3> LAST_HOLDER_POS = new HashMap<>();
    private static final Map<UUID, Double> VEHICLE_STRAIN = new HashMap<>();

    public static void tick(TwoFrogsState state, FrogRole role, Frog frog) {
        if (state.getBehavior(role) == FrogBehavior.DORMANT) {
            return;
        }
        UUID id = frog.getUUID();

        if (frog.isLeashed()) {
            tickLeash(state, role, frog, id);
        } else {
            LEASHED_SECONDS.remove(id);
            LAST_HOLDER_POS.remove(id);
        }

        if (frog.isPassenger()) {
            tickVehicle(state, role, frog, id);
        } else {
            VEHICLE_STRAIN.remove(id);
        }
    }

    // ---------- Leash ----------

    private static void tickLeash(TwoFrogsState state, FrogRole role, Frog frog, UUID id) {
        Entity holder = frog.getLeashHolder();
        if (holder == null) {
            return;
        }

        int secondsLeashed = LEASHED_SECONDS.merge(id, 1, Integer::sum);
        double agitation = Mth.clamp(state.getAgitation(role) / FrogTuning.MAX_AGITATION, 0.0, 1.0);

        double chance = Mth.lerp(agitation * agitation,
                FrogTuning.LEASH_ESCAPE_CHANCE_AT_MIN, FrogTuning.LEASH_ESCAPE_CHANCE_AT_MAX);

        // Time spent leashed
        double timeShare = Math.min(secondsLeashed, FrogTuning.LEASH_TIME_CAP_SECONDS)
                / (double) FrogTuning.LEASH_TIME_CAP_SECONDS;
        chance *= 1.0 + timeShare * FrogTuning.LEASH_TIME_FACTOR_MAX;

        // Leash tension (vanilla snaps the lead at 10 blocks, so we scale from 4 to 10)
        double tension = Mth.clamp((frog.distanceTo(holder) - 4.0) / 6.0, 0.0, 1.0);
        chance *= 1.0 + tension * FrogTuning.LEASH_TENSION_FACTOR_MAX;

        // Whether the holder is moving (compared with where they were a second ago)
        Vec3 current = holder.position();
        Vec3 previous = LAST_HOLDER_POS.put(id, current);
        if (previous != null && previous.distanceToSqr(current) > 2.25) {
            chance *= FrogTuning.LEASH_MOVING_FACTOR;
        }

        // Whether the partner is close by
        chance *= partnerFactor(state, role);

        // Previous escapes
        int escapes = Math.min(state.getEscapeCount(role), FrogTuning.LEASH_HISTORY_MAX_ESCAPES);
        chance *= 1.0 + escapes * FrogTuning.LEASH_HISTORY_FACTOR_PER_ESCAPE;

        chance = Math.min(chance, FrogTuning.LEASH_ESCAPE_CHANCE_CAP);

        if (frog.getRandom().nextDouble() < chance) {
            // Vanilla leash break: the lead drops once as a normal item.
            frog.dropLeash(true, true);
            LEASHED_SECONDS.remove(id);
            LAST_HOLDER_POS.remove(id);
            TheTwoFrogs.LOGGER.info("Frog {} broke free of its leash (agitation {}).",
                    role, String.format("%.2f", state.getAgitation(role)));
        }
    }

    private static double partnerFactor(TwoFrogsState state, FrogRole role) {
        FrogRole partner = role == FrogRole.A ? FrogRole.B : FrogRole.A;
        FrogLocation mine = state.getLastKnown(role);
        FrogLocation theirs = state.getLastKnown(partner);
        if (mine == null || theirs == null || !mine.dimension().equals(theirs.dimension())) {
            return 1.0;
        }
        double dx = mine.pos().getX() - theirs.pos().getX();
        double dz = mine.pos().getZ() - theirs.pos().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= FrogTuning.PARTNER_NEAR_BLOCKS) {
            return FrogTuning.PARTNER_NEAR_FACTOR;
        }
        if (distance <= FrogTuning.PARTNER_MID_BLOCKS) {
            return FrogTuning.PARTNER_MID_FACTOR;
        }
        return 1.0;
    }

    // ---------- Boats and minecarts ----------

    private static void tickVehicle(TwoFrogsState state, FrogRole role, Frog frog, UUID id) {
        Entity vehicle = frog.getVehicle();
        if (vehicle == null) {
            return;
        }

        double agitation = Mth.clamp(state.getAgitation(role) / FrogTuning.MAX_AGITATION, 0.0, 1.0);
        double struggleChance = Mth.lerp(agitation * agitation,
                FrogTuning.STRUGGLE_CHANCE_AT_MIN, FrogTuning.STRUGGLE_CHANCE_AT_MAX);

        if (frog.getRandom().nextDouble() >= struggleChance) {
            return;
        }

        double added = Mth.lerp(agitation,
                FrogTuning.STRAIN_PER_STRUGGLE_AT_MIN, FrogTuning.STRAIN_PER_STRUGGLE_AT_MAX);
        double strain = VEHICLE_STRAIN.merge(id, added, Double::sum);
        double limit = strainLimit(vehicle);

        TheTwoFrogs.LOGGER.info("Frog {} struggles in its {} (strain {} / {}).", role,
                vehicle.getType().getDescriptionId(), String.format("%.1f", strain), limit);

        DamageSource source = frog.level().damageSources().mobAttack(frog);

        if (strain < limit) {
            // Visible wobble using the vanilla hit animation. This does not break the vehicle by itself.
            vehicle.hurt(source, FrogTuning.STRUGGLE_WOBBLE_DAMAGE);
            return;
        }

        breakFree(frog, vehicle, source, role, id);
    }

    private static double strainLimit(Entity vehicle) {
        if (vehicle instanceof Boat) {
            return FrogTuning.STRAIN_LIMIT_BOAT;
        }
        if (vehicle instanceof AbstractMinecart) {
            return FrogTuning.STRAIN_LIMIT_MINECART;
        }
        return FrogTuning.STRAIN_LIMIT_OTHER;
    }

    private static void breakFree(Frog frog, Entity vehicle, DamageSource source, FrogRole role, UUID id) {
        frog.stopRiding();
        VEHICLE_STRAIN.remove(id);

        if (vehicle instanceof Boat || vehicle instanceof AbstractMinecart) {
            // Enough damage for vanilla to destroy it normally (including its normal drops).
            vehicle.hurt(source, 1000.0F);
        }
        TheTwoFrogs.LOGGER.info("Frog {} broke free of its {}.", role, vehicle.getType().getDescriptionId());
    }
}