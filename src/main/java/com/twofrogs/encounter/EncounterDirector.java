package com.twofrogs.encounter;

import com.twofrogs.TheTwoFrogs;
import com.twofrogs.encounter.Carriers.Carrier;
import com.twofrogs.encounter.EncounterSpawner.Kind;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the dangers of the road. Only players carrying a captured special frog far from the habitat are affected.
 * Every hostile encounter is announced by a warning (a distant sound and a line of text) several seconds before
 * anything appears, never spawns close to a player, and is capped and cleaned up.
 */
public final class EncounterDirector {

    public static final String TAG = "twofrogs_encounter";

    private static final class Memory {
        long nextEncounter = 0;
        long nextCroak = 0;
        int milestone = 0;
    }

    private static final class Active {
        final long spawnTick;
        final UUID owner;

        Active(long spawnTick, UUID owner) {
            this.spawnTick = spawnTick;
            this.owner = owner;
        }
    }

    private static final class Pending {
        final UUID owner;
        final Kind kind;
        final BlockPos pos;
        final List<EntityType<?>> mobs;
        final boolean helmets;
        final boolean hunt;
        final long spawnAt;
        boolean secondCue = false;

        Pending(UUID owner, Kind kind, BlockPos pos, List<EntityType<?>> mobs, boolean helmets, boolean hunt, long spawnAt) {
            this.owner = owner;
            this.kind = kind;
            this.pos = pos;
            this.mobs = mobs;
            this.helmets = helmets;
            this.hunt = hunt;
            this.spawnAt = spawnAt;
        }
    }

    private static final Map<FrogRole, Memory> MEMORY = new EnumMap<>(FrogRole.class);
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    private static long stormAt = 0;

    private EncounterDirector() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) {
                onSecond(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MEMORY.clear();
            PENDING.clear();
            ACTIVE.clear();
            stormAt = 0;
        });
    }

    // ---------- Once a second ----------

    private static void onSecond(MinecraftServer server) {
        ServerLevel level = server.overworld();
        EncounterSettings settings = EncounterSettings.get(server);
        long now = level.getGameTime();

        cleanup(level, now);
        if (!settings.isEnabled() || level.getDifficulty() == Difficulty.PEACEFUL) {
            PENDING.clear();
            stormAt = 0;
            return;
        }
        processPending(level, now);

        TwoFrogsState state = TwoFrogsState.get(server);
        List<Carrier> carriers = Carriers.find(server);
        double maxAgitation = 0.0;
        for (Carrier carrier : carriers) {
            maxAgitation = Math.max(maxAgitation, carrier.agitation());
            handleCarrier(level, settings, state, carrier, now);
        }
        handleStorm(level, settings, carriers, maxAgitation, now);
    }

    private static void handleCarrier(ServerLevel level, EncounterSettings settings, TwoFrogsState state,
                                      Carrier carrier, long now) {
        Memory memory = MEMORY.computeIfAbsent(carrier.role(), r -> new Memory());
        RandomSource random = carrier.player().getRandom();

        // Camps are resting places: they calm the frog and keep the road quiet.
        if (settings.nearestCamp(carrier.frog().blockPosition(), EncounterTuning.CAMP_CALM_RADIUS).isPresent()) {
            double calmed = state.getAgitation(carrier.role()) - EncounterTuning.CAMP_CALM_PER_SECOND;
            state.setAgitation(carrier.role(), Math.max(0.0, calmed));
            memory.nextEncounter = Math.max(memory.nextEncounter, now + EncounterTuning.MIN_GAP_TICKS);
            return;
        }
        if (carrier.progress() < 0.0) {
            return; // still close to the habitat: the road is quiet
        }

        int milestone = (int) Math.floor(carrier.progress() * 10.0);
        if (memory.nextEncounter == 0) {
            memory.nextEncounter = now + EncounterTuning.FIRST_GRACE_SECONDS * 20L;
            memory.nextCroak = now + 20L * 20L;
            memory.milestone = milestone;
            return;
        }

        // A new stretch of road: perhaps an abandoned camp ahead.
        if (milestone > memory.milestone) {
            memory.milestone = milestone;
            if (random.nextDouble() < EncounterTuning.CAMP_CHANCE) {
                tryCamp(level, settings, carrier, false);
            }
        }

        // The Croak
        if (carrier.agitation() >= EncounterTuning.CROAK_MIN_AGITATION && now >= memory.nextCroak) {
            doCroak(level, settings, carrier, now);
            double t = Mth.clamp((carrier.agitation() - EncounterTuning.CROAK_MIN_AGITATION)
                    / (100.0 - EncounterTuning.CROAK_MIN_AGITATION), 0.0, 1.0);
            double seconds = Mth.lerp(t, EncounterTuning.CROAK_INTERVAL_MAX_SECONDS,
                    EncounterTuning.CROAK_INTERVAL_MIN_SECONDS) * (0.8 + random.nextDouble() * 0.4);
            memory.nextCroak = now + (long) (seconds * 20.0);
        }

        // An ordinary encounter
        if (now >= memory.nextEncounter && !hasPending(carrier.player().getUUID())) {
            Kind kind = chooseKind(level, carrier);
            boolean scheduled = kind != null && schedule(level, settings, carrier, kind, false, false, now);
            if (scheduled) {
                double base = Mth.lerp(carrier.progress(), EncounterTuning.INTERVAL_AT_START_SECONDS,
                        EncounterTuning.INTERVAL_AT_END_SECONDS);
                double scale = Math.max(0.05, settings.getFrequency() * (0.6 + 0.8 * carrier.agitation() / 100.0));
                double seconds = base / scale * (0.75 + random.nextDouble() * 0.5);
                memory.nextEncounter = now + Math.max(EncounterTuning.MIN_GAP_TICKS, (long) (seconds * 20.0));
            } else {
                memory.nextEncounter = now + EncounterTuning.RETRY_TICKS;
            }
        }
    }

    // ---------- Choosing and scheduling ----------

    private static Kind chooseKind(ServerLevel level, Carrier carrier) {
        ServerPlayer player = carrier.player();
        BlockPos pos = player.blockPosition();
        boolean night = EncounterSpawner.isNight(level);
        boolean inWater = player.isInWater() || player.getVehicle() instanceof Boat;
        boolean cave = EncounterSpawner.isCave(level, pos);

        double water = inWater ? 5.0 : 0.0;
        double ambush = cave ? 5.0 : 0.0;
        double patrol = night ? 4.0 + carrier.progress() * 3.0 : (carrier.progress() > 0.3 ? carrier.progress() * 2.5 : 0.0);
        if (inWater) {
            patrol *= 0.3;
        }
        double total = water + ambush + patrol;
        if (total <= 0.0) {
            return null;
        }
        double roll = level.getRandom().nextDouble() * total;
        if (roll < water) {
            return Kind.WATER;
        }
        if (roll < water + ambush) {
            return Kind.AMBUSH;
        }
        return Kind.PATROL;
    }

    /**
     * Picks a place and the mobs, then starts the warning. The mobs appear only after the warning time.
     * 'force' (debug) relaxes the light conditions and shortens the warning.
     */
    private static boolean schedule(ServerLevel level, EncounterSettings settings, Carrier carrier, Kind kind,
                                    boolean croak, boolean force, long now) {
        ServerPlayer player = carrier.player();
        RandomSource random = player.getRandom();
        UUID owner = player.getUUID();

        double progress = Math.max(0.0, carrier.progress());
        double intensity = (0.2 + 0.8 * progress) * settings.getDifficulty();
        int count = (int) Math.round(1.0 + intensity * 3.0 + random.nextDouble());
        count = Mth.clamp(count, 1, EncounterTuning.MAX_GROUP);
        if (kind != Kind.PATROL) {
            count = Math.min(count, 3);
        }

        int cap = progress >= EncounterTuning.LATE_PROGRESS ? EncounterTuning.PER_PLAYER_CAP_LATE
                : EncounterTuning.PER_PLAYER_CAP;
        count = Math.min(count, cap - activeFor(owner) - pendingMobsFor(owner));
        if (count <= 0 || ACTIVE.size() + pendingMobsTotal() + count > EncounterTuning.GLOBAL_CAP) {
            return false;
        }

        double min;
        double max;
        int warn;
        switch (kind) {
            case AMBUSH -> {
                min = EncounterTuning.AMBUSH_MIN;
                max = EncounterTuning.AMBUSH_MAX;
                warn = EncounterTuning.AMBUSH_WARN_TICKS;
            }
            case WATER -> {
                min = EncounterTuning.WATER_MIN;
                max = EncounterTuning.WATER_MAX;
                warn = EncounterTuning.WATER_WARN_TICKS;
            }
            default -> {
                min = EncounterTuning.PATROL_MIN;
                max = EncounterTuning.PATROL_MAX;
                warn = croak ? EncounterTuning.CROAK_WARN_TICKS : EncounterTuning.PATROL_WARN_TICKS;
            }
        }
        if (force) {
            warn = EncounterTuning.FORCE_WARN_TICKS;
        }

        boolean night = EncounterSpawner.isNight(level);
        boolean daytimeParty = kind == Kind.PATROL && !night;
        Optional<BlockPos> spot = EncounterSpawner.findSpawn(level, player, kind, min, max,
                kind == Kind.PATROL && night, force);
        if (spot.isEmpty()) {
            return false;
        }

        boolean cave = kind == Kind.AMBUSH && EncounterSpawner.isCave(level, spot.get());
        List<EntityType<?>> mobs = EncounterSpawner.pick(level, spot.get(), kind, count, cave);
        Pending pending = new Pending(owner, kind, spot.get(), mobs, daytimeParty, kind == Kind.PATROL, now + warn);
        PENDING.add(pending);

        // The warning: a sound from where they will appear, and a line of text.
        level.playSound(null, spot.get().getX(), spot.get().getY(), spot.get().getZ(),
                EncounterSpawner.ambient(mobs.get(0)), SoundSource.HOSTILE, 2.5F, 0.7F);
        String text;
        if (croak) {
            text = "Answering growls rise in the distance.";
        } else if (kind == Kind.AMBUSH) {
            text = "Something skitters in the dark ahead.";
        } else if (kind == Kind.WATER) {
            text = "The water nearby stirs and bubbles.";
        } else {
            text = night ? "You hear shuffling in the dark..." : "Distant voices carry on the wind...";
        }
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
        return true;
    }

    private static void processPending(ServerLevel level, long now) {
        Iterator<Pending> it = new ArrayList<>(PENDING).iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(pending.owner);
            if (player == null || !player.isAlive()) {
                PENDING.remove(pending);
                continue;
            }
            if (!pending.secondCue && now >= pending.spawnAt - 40) {
                pending.secondCue = true;
                level.playSound(null, pending.pos.getX(), pending.pos.getY(), pending.pos.getZ(),
                        EncounterSpawner.ambient(pending.mobs.get(0)), SoundSource.HOSTILE, 2.5F, 0.55F);
            }
            if (now >= pending.spawnAt) {
                PENDING.remove(pending);
                spawnGroup(level, player, pending, now);
            }
        }
    }

    private static void spawnGroup(ServerLevel level, ServerPlayer player, Pending pending, long now) {
        RandomSource random = level.getRandom();
        for (EntityType<?> type : pending.mobs) {
            BlockPos at = pending.pos;
            if (pending.kind != Kind.WATER) {
                for (int tries = 0; tries < 6; tries++) {
                    BlockPos base = pending.pos.offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3);
                    BlockPos stand = com.twofrogs.frog.FrogTerrain.findStandable(level, base);
                    if (stand != null && EncounterSpawner.farFromPlayers(level, stand)) {
                        at = stand;
                        break;
                    }
                }
            }
            if (!level.hasChunkAt(at)) {
                continue;
            }

            Entity entity = type.create(level);
            if (!(entity instanceof Mob mob)) {
                continue;
            }
            mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);

            // A daytime hunting party wears helmets so the sun does not burn them.
            if (pending.helmets && (type == EntityType.ZOMBIE || type == EntityType.SKELETON || type == EntityType.STRAY)) {
                mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                mob.setDropChance(EquipmentSlot.HEAD, 0.0F);
            }
            mob.addTag(TAG);
            if (pending.hunt && player.distanceToSqr(mob) <= 48.0 * 48.0) {
                mob.setTarget(player);
            }
            level.addFreshEntity(mob);
            ACTIVE.put(mob.getUUID(), new Active(now, pending.owner));
        }
        TheTwoFrogs.LOGGER.info("An encounter appeared ({}, {} mobs).", pending.kind, pending.mobs.size());
    }

    // ---------- Counting and cleaning up ----------

    private static boolean hasPending(UUID owner) {
        for (Pending p : PENDING) {
            if (p.owner.equals(owner)) {
                return true;
            }
        }
        return false;
    }

    private static int activeFor(UUID owner) {
        int n = 0;
        for (Active a : ACTIVE.values()) {
            if (a.owner.equals(owner)) {
                n++;
            }
        }
        return n;
    }

    private static int pendingMobsFor(UUID owner) {
        int n = 0;
        for (Pending p : PENDING) {
            if (p.owner.equals(owner)) {
                n += p.mobs.size();
            }
        }
        return n;
    }

    private static int pendingMobsTotal() {
        int n = 0;
        for (Pending p : PENDING) {
            n += p.mobs.size();
        }
        return n;
    }

    private static void cleanup(ServerLevel level, long now) {
        Iterator<Map.Entry<UUID, Active>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Active> entry = it.next();
            Entity entity = level.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                it.remove();
                continue;
            }
            Player nearest = level.getNearestPlayer(entity, EncounterTuning.DESPAWN_FAR_BLOCKS);
            boolean old = now - entry.getValue().spawnTick > EncounterTuning.MOB_LIFETIME_TICKS;
            if (nearest == null) {
                entity.discard();
                it.remove();
            } else if (old && entity.distanceTo(nearest) > EncounterTuning.LINGER_NEAR_BLOCKS) {
                entity.discard();
                it.remove();
            }
        }
    }

    // ---------- The Croak ----------

    private static void doCroak(ServerLevel level, EncounterSettings settings, Carrier carrier, long now) {
        Frog frog = carrier.frog();
        level.playSound(null, frog.getX(), frog.getY(), frog.getZ(),
                SoundEvents.FROG_AMBIENT, SoundSource.NEUTRAL, 3.0F, 0.45F);
        level.playSound(null, frog.getX(), frog.getY(), frog.getZ(),
                SoundEvents.FROG_HURT, SoundSource.NEUTRAL, 2.0F, 0.5F);

        Component message = Component.literal("The frog croaks, loud and long. Something answers from far away.")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC);
        for (ServerPlayer player : level.players()) {
            if (player.distanceTo(frog) <= EncounterTuning.CROAK_HEAR_BLOCKS) {
                player.displayClientMessage(message, true);
            }
        }
        if (level.getRandom().nextDouble() < EncounterTuning.CROAK_PATROL_CHANCE
                && !hasPending(carrier.player().getUUID())) {
            schedule(level, settings, carrier, Kind.PATROL, true, false, now);
        }
    }

    // ---------- Storms ----------

    private static void handleStorm(ServerLevel level, EncounterSettings settings, List<Carrier> carriers,
                                    double maxAgitation, long now) {
        if (stormAt != 0) {
            if (now >= stormAt) {
                startStorm(level, settings, now);
                stormAt = 0;
            }
            return;
        }
        if (carriers.isEmpty() || maxAgitation < EncounterTuning.STORM_MIN_AGITATION || level.isThundering()) {
            return;
        }
        if (now - settings.getLastStormTick() < EncounterTuning.STORM_COOLDOWN_TICKS) {
            return;
        }
        if (level.getRandom().nextDouble() > EncounterTuning.STORM_CHANCE_PER_SECOND * settings.getFrequency()) {
            return;
        }
        stormAt = now + EncounterTuning.STORM_WARN_TICKS;
        Component warning = Component.literal("The air tastes of iron. A storm is gathering.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        for (Carrier carrier : carriers) {
            carrier.player().displayClientMessage(warning, true);
        }
    }

    private static void startStorm(ServerLevel level, EncounterSettings settings, long now) {
        level.setWeatherParameters(0, EncounterTuning.STORM_DURATION_TICKS, true, true);
        settings.setLastStormTick(now);
    }

    // ---------- Camps ----------

    private static boolean tryCamp(ServerLevel level, EncounterSettings settings, Carrier carrier, boolean force) {
        if (!force && settings.camps().size() >= EncounterTuning.MAX_CAMPS) {
            return false;
        }
        ServerPlayer player = carrier.player();
        RandomSource random = player.getRandom();
        Vec3 look = player.getLookAngle();
        double heading = Math.atan2(look.z, look.x);

        for (int tries = 0; tries < 12; tries++) {
            double angle = heading + (random.nextDouble() - 0.5) * Math.toRadians(70.0);
            double distance = EncounterTuning.CAMP_AHEAD_MIN
                    + random.nextDouble() * (EncounterTuning.CAMP_AHEAD_MAX - EncounterTuning.CAMP_AHEAD_MIN);
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            if (!level.hasChunkAt(new BlockPos(x, 64, z))) {
                continue;
            }
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!EncounterCamp.suitable(level, pos)) {
                continue;
            }
            if (settings.nearestCamp(pos, EncounterTuning.CAMP_MIN_SPACING).isPresent()) {
                continue;
            }
            EncounterCamp.build(level, pos, random);
            settings.addCamp(pos);
            TheTwoFrogs.LOGGER.info("A traveler's camp was placed.");
            return true;
        }
        return false;
    }

    // ---------- Debug and status ----------

    public static String trigger(MinecraftServer server, ServerPlayer player, String kind) {
        ServerLevel level = server.overworld();
        EncounterSettings settings = EncounterSettings.get(server);
        long now = level.getGameTime();
        if (level.getDifficulty() == Difficulty.PEACEFUL && !kind.equals("storm") && !kind.equals("camp")) {
            return "The world is on Peaceful, so no hostile encounters can appear.";
        }

        Carrier carrier = null;
        for (Carrier c : Carriers.find(server)) {
            if (c.player() == player) {
                carrier = c;
                break;
            }
        }
        double progress = carrier != null && carrier.progress() >= 0.0 ? carrier.progress() : 0.6;
        double agitation = carrier != null ? carrier.agitation() : 60.0;
        Carrier used = carrier != null ? carrier : new Carrier(player, null, null, progress, agitation);

        switch (kind) {
            case "patrol":
                return schedule(level, settings, used, Kind.PATROL, false, true, now)
                        ? "A patrol is coming. Listen for the warning." : "Could not find a place for a patrol.";
            case "ambush":
                return schedule(level, settings, used, Kind.AMBUSH, false, true, now)
                        ? "An ambush is coming. Listen for the warning." : "Could not find a place for an ambush.";
            case "water":
                return schedule(level, settings, used, Kind.WATER, false, true, now)
                        ? "Something is stirring in the water." : "No deep enough water was found near you.";
            case "croak":
                if (carrier == null) {
                    return "You need to be carrying a captured special frog (leashed) for a croak.";
                }
                doCroak(level, settings, carrier, now);
                return "The frog croaked.";
            case "storm":
                startStorm(level, settings, now);
                return "A storm is breaking.";
            case "camp":
                return tryCamp(level, settings, used, true) ? "A camp was placed ahead of you (look 50 to 80 blocks forward)."
                        : "Could not find a suitable place for a camp ahead.";
            default:
                return "Unknown encounter: " + kind;
        }
    }

    public static String status(MinecraftServer server) {
        EncounterSettings settings = EncounterSettings.get(server);
        StringBuilder sb = new StringBuilder("--- Encounters ---");
        sb.append("\nEnabled: ").append(settings.isEnabled());
        sb.append("\nFrequency: ").append(String.format("%.2f", settings.getFrequency()));
        sb.append("\nDifficulty: ").append(String.format("%.2f", settings.getDifficulty()));
        sb.append("\nActive mobs: ").append(ACTIVE.size()).append(", warnings pending: ").append(PENDING.size());
        sb.append("\nCamps placed: ").append(settings.camps().size()).append(" (max ").append(EncounterTuning.MAX_CAMPS).append(")");
        sb.append("\nProgress override: ").append(Carriers.progressOverride < 0 ? "off" : Carriers.progressOverride);
        long now = server.overworld().getGameTime();
        List<Carrier> carriers = Carriers.find(server);
        if (carriers.isEmpty()) {
            sb.append("\nNobody is carrying a captured special frog.");
        }
        for (Carrier c : carriers) {
            Memory memory = MEMORY.get(c.role());
            long seconds = memory == null ? 0 : Math.max(0, (memory.nextEncounter - now) / 20);
            sb.append("\nFrog ").append(c.role()).append(" carried by ").append(c.player().getName().getString())
                    .append(": journey ").append(c.progress() < 0 ? "not started" : String.format("%.0f%%", c.progress() * 100.0))
                    .append(", agitation ").append(String.format("%.0f", c.agitation()))
                    .append(", next encounter in about ").append(seconds).append(" s");
        }
        return sb.toString();
    }
}