package com.twofrogs.encounter;

import com.twofrogs.frog.SpecialFrogManager;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Finds the players who are carrying a captured special frog, and how far along their journey they are. */
public final class Carriers {

    /** progress is -1 when the frog is still too close to the habitat for any encounter. */
    public record Carrier(ServerPlayer player, Frog frog, FrogRole role, double progress, double agitation) {
    }

    /** Debug: pretend the journey is this far along (0 to 1). Negative means use the real distance. */
    public static double progressOverride = -1.0;

    private Carriers() {
    }

    public static List<Carrier> find(MinecraftServer server) {
        List<Carrier> result = new ArrayList<>();
        TwoFrogsState state = TwoFrogsState.get(server);
        Optional<BlockPos> habitat = state.getHabitatCenter();
        if (habitat.isEmpty()) {
            return result;
        }
        ServerLevel level = server.overworld();

        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> id = state.getFrogUuid(role);
            if (id.isEmpty() || state.getBehavior(role) != FrogBehavior.CAPTURED) {
                continue;
            }
            Frog frog = SpecialFrogManager.findFrog(server, id.get());
            if (frog == null || frog.level() != level) {
                continue;
            }
            ServerPlayer holder = holderOf(level, frog);
            if (holder == null) {
                continue;
            }

            double dx = frog.getX() - (habitat.get().getX() + 0.5);
            double dz = frog.getZ() - (habitat.get().getZ() + 0.5);
            double distance = Math.sqrt(dx * dx + dz * dz);
            double progress;
            if (progressOverride >= 0.0) {
                progress = Mth.clamp(progressOverride, 0.0, 1.0);
            } else if (distance < EncounterTuning.MIN_JOURNEY_BLOCKS) {
                progress = -1.0;
            } else {
                progress = Mth.clamp((distance - EncounterTuning.MIN_JOURNEY_BLOCKS)
                        / (EncounterTuning.FULL_JOURNEY_BLOCKS - EncounterTuning.MIN_JOURNEY_BLOCKS), 0.0, 1.0);
            }
            result.add(new Carrier(holder, frog, role, progress, state.getAgitation(role)));
        }
        return result;
    }

    /** Whoever is holding the lead, riding with the frog, or standing right next to it. */
    private static ServerPlayer holderOf(ServerLevel level, Frog frog) {
        Entity leash = frog.getLeashHolder();
        if (leash instanceof ServerPlayer player) {
            return player;
        }
        Entity vehicle = frog.getVehicle();
        if (vehicle != null) {
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof ServerPlayer player) {
                    return player;
                }
            }
        }
        Player near = level.getNearestPlayer(frog, 12.0);
        return near instanceof ServerPlayer player ? player : null;
    }
}