package com.twofrogs.frog;

import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Measures how fast the special frogs really move (for /twofrogs debug speed). */
public final class SpeedProbe {

    private static final int WINDOW = 100; // 5 seconds
    private static final Map<FrogRole, Track> TRACKS = new EnumMap<>(FrogRole.class);

    private static final class Track {
        Vec3 last = null;
        final double[] ring = new double[WINDOW];
        int index = 0;
        int filled = 0;
    }

    private SpeedProbe() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SpeedProbe::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TRACKS.clear());
    }

    private static void onServerTick(MinecraftServer server) {
        TwoFrogsState state = TwoFrogsState.get(server);
        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                continue;
            }
            Track track = TRACKS.computeIfAbsent(role, r -> new Track());
            Frog frog = SpecialFrogManager.findFrog(server, uuid.get());
            if (frog == null) {
                track.last = null;
                continue;
            }
            Vec3 pos = frog.position();
            double step = 0.0;
            if (track.last != null) {
                step = Math.hypot(pos.x - track.last.x, pos.z - track.last.z);
                if (step > 4.0) {
                    step = 0.0; // a rescue or teleport, not real movement
                }
            }
            track.last = pos;
            track.ring[track.index] = step;
            track.index = (track.index + 1) % WINDOW;
            track.filled = Math.min(WINDOW, track.filled + 1);
        }
    }

    public static String report(MinecraftServer server) {
        TwoFrogsState state = TwoFrogsState.get(server);
        StringBuilder sb = new StringBuilder("--- Frog speed (horizontal blocks per second) ---");
        for (FrogRole role : FrogRole.values()) {
            sb.append("\nFrog ").append(role).append(" (").append(state.getBehavior(role)).append("): ");
            Track track = TRACKS.get(role);
            if (track == null || track.filled < 20) {
                sb.append("not enough data yet (the frog must be loaded for a second or two)");
                continue;
            }
            double total = 0.0;
            for (int i = 0; i < track.filled; i++) {
                total += track.ring[i];
            }
            double average = total / (track.filled / 20.0);

            double best = 0.0;
            for (int start = 0; start + 20 <= track.filled; start++) {
                double sum = 0.0;
                for (int i = 0; i < 20; i++) {
                    sum += track.ring[(track.index - track.filled + start + i + WINDOW * 2) % WINDOW];
                }
                best = Math.max(best, sum);
            }
            sb.append(String.format("average %.1f over the last %d s, best single second %.1f",
                    average, track.filled / 20, best));
        }
        sb.append("\nFor comparison: a player walks 4.3, sprints 5.6, and sprint-jumps about 7.1.");
        return sb.toString();
    }
}