package com.twofrogs.extras;

import com.twofrogs.frog.SpecialFrogManager;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.frog.Frog;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Atmosphere extras. Safe to delete: remove this file and the register() call in TheTwoFrogs. */
public class ExtrasManager {

    private static final double RANGE_BLOCKS = 40.0;
    private static final double HEAR_BLOCKS = 48.0;
    private static final int GROUP_RADIUS = 24;
    private static final int GROUP_MAX = 12;

    private static final Map<FrogRole, FrogBehavior> LAST_SEEN = new EnumMap<>(FrogRole.class);
    private static final List<PendingSound> PENDING = new ArrayList<>();
    private static int cooldown = 0;

    private static class PendingSound {
        final ServerLevel level;
        final double x;
        final double y;
        final double z;
        final float pitch;
        int delay;

        PendingSound(ServerLevel level, double x, double y, double z, int delay, float pitch) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.delay = delay;
            this.pitch = pitch;
        }
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ExtrasManager::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LAST_SEEN.clear();
            PENDING.clear();
            cooldown = 0;
        });
    }

    private static void onServerTick(MinecraftServer server) {
        tickPending();

        TwoFrogsState state = TwoFrogsState.get(server);
        if (state.getFrogUuid(FrogRole.A).isEmpty() || state.getFrogUuid(FrogRole.B).isEmpty()) {
            return;
        }
        if (server.getTickCount() % 20 == 0) {
            watchBehavior(server, state);
        }
        tickPulse(server, state);
    }

    // ---------- Extra 1: a reaction the very first time a frog escapes ----------

    private static void watchBehavior(MinecraftServer server, TwoFrogsState state) {
        int totalEscapes = state.getEscapeCount(FrogRole.A) + state.getEscapeCount(FrogRole.B);
        for (FrogRole role : FrogRole.values()) {
            FrogBehavior now = state.getBehavior(role);
            FrogBehavior before = LAST_SEEN.put(role, now);
            if (before != null && before != FrogBehavior.ESCAPED
                    && now == FrogBehavior.ESCAPED && totalEscapes == 1) {
                Frog frog = SpecialFrogManager.findFrog(server, state.getFrogUuid(role).get());
                if (frog != null) {
                    scheduleGroup(state, frog);
                }
            }
        }
    }

    private static void scheduleGroup(TwoFrogsState state, Frog source) {
        ServerLevel level = (ServerLevel) source.level();
        List<Frog> nearby = level.getEntitiesOfClass(Frog.class, source.getBoundingBox().inflate(GROUP_RADIUS),
                other -> !state.isSpecial(other.getUUID()));
        int count = Math.min(nearby.size(), GROUP_MAX);
        for (int i = 0; i < count; i++) {
            Frog f = nearby.get(i);
            PENDING.add(new PendingSound(level, f.getX(), f.getY(), f.getZ(),
                    10 + source.getRandom().nextInt(70), 0.7F + source.getRandom().nextFloat() * 0.5F));
        }
    }

    private static void tickPending() {
        Iterator<PendingSound> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingSound s = it.next();
            if (--s.delay <= 0) {
                s.level.playSound(null, s.x, s.y, s.z, SoundEvents.FROG_AMBIENT, SoundSource.NEUTRAL, 1.0F, s.pitch);
                it.remove();
            }
        }
    }

    // ---------- Extra 2: a warning pulse when both frogs are out and getting close ----------

    private static void tickPulse(MinecraftServer server, TwoFrogsState state) {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (state.getBehavior(FrogRole.A) == FrogBehavior.DORMANT
                || state.getBehavior(FrogRole.B) == FrogBehavior.DORMANT) {
            cooldown = 20;
            return;
        }
        Frog a = SpecialFrogManager.findFrog(server, state.getFrogUuid(FrogRole.A).get());
        Frog b = SpecialFrogManager.findFrog(server, state.getFrogUuid(FrogRole.B).get());
        if (a == null || b == null || a.level() != b.level()) {
            cooldown = 20;
            return;
        }
        double distance = a.distanceTo(b);
        if (distance > RANGE_BLOCKS) {
            cooldown = 20;
            return;
        }

        double closeness = 1.0 - distance / RANGE_BLOCKS;
        float volume = (float) Mth.lerp(closeness, 0.3, 1.2);
        float pitch = (float) Mth.lerp(closeness, 0.8, 1.3);

        ServerLevel level = (ServerLevel) a.level();
        for (ServerPlayer player : level.players()) {
            if (player.distanceTo(a) <= HEAR_BLOCKS || player.distanceTo(b) <= HEAR_BLOCKS) {
                player.playNotifySound(SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, volume, pitch);
            }
        }
        cooldown = (int) Mth.lerp(closeness, 36.0, 8.0);
    }
}