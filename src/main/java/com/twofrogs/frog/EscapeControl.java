package com.twofrogs.frog;

import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.frog.Frog;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * While a special frog is free (escaped, not leashed, not riding), its vanilla brain is skipped
 * (see FrogBrainMixin), so it cannot fight our movement: it would follow slimeballs, look for water,
 * wander, long-jump around and chase slimes. Everything else (physics, jumping, collisions) keeps working.
 * This set is rebuilt every tick from the saved frog state, so nothing can get stuck after a restart.
 */
public final class EscapeControl {

    /** A leftover tag from an earlier version that switched the frog's AI off completely. */
    private static final String LEGACY_TAG = "twofrogs_noai";

    private static final Set<UUID> CONTROLLED = new HashSet<>();

    private EscapeControl() {
    }

    public static boolean isControlled(UUID frogId) {
        return CONTROLLED.contains(frogId);
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(EscapeControl::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CONTROLLED.clear());
    }

    private static void onServerTick(MinecraftServer server) {
        TwoFrogsState state = TwoFrogsState.get(server);

        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                continue;
            }
            Frog frog = SpecialFrogManager.findFrog(server, uuid.get());
            if (frog == null) {
                CONTROLLED.remove(uuid.get());
                continue;
            }

            // Undo the old "AI off" approach for frogs saved by the earlier version.
            if (frog.getTags().contains(LEGACY_TAG)) {
                frog.setNoAi(false);
                frog.removeTag(LEGACY_TAG);
            }

            boolean free = state.getBehavior(role) == FrogBehavior.ESCAPED
                    && !frog.isLeashed() && !frog.isPassenger();
            if (free) {
                if (CONTROLLED.add(uuid.get())) {
                    clearBrainDrives(frog);
                }
            } else if (CONTROLLED.remove(uuid.get())) {
                clearBrainDrives(frog); // the brain starts fresh when it resumes
            }
        }
    }

    private static void clearBrainDrives(Frog frog) {
        frog.getNavigation().stop();
        frog.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        frog.getBrain().eraseMemory(MemoryModuleType.TEMPTING_PLAYER);
        frog.getBrain().eraseMemory(MemoryModuleType.IS_TEMPTED);
        frog.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }
}