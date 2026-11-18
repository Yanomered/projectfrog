package com.twofrogs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogLocation;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import com.twofrogs.state.WorldPlanState;
import com.twofrogs.state.WorldPlanState.SiteType;
import com.twofrogs.world.HabitatSpawner;
import com.twofrogs.world.SitePlanner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class TwoFrogsCommand {

    private static final int DEBUG_HABITAT_RADIUS = 48;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("twofrogs")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(TwoFrogsCommand::status))
                .then(Commands.literal("plan")
                        .executes(TwoFrogsCommand::plan)
                        .then(Commands.literal("reset")
                                .executes(TwoFrogsCommand::planReset)))
                .then(Commands.literal("sites")
                        .executes(TwoFrogsCommand::sites))
                .then(Commands.literal("spawnhabitat")
                        .executes(TwoFrogsCommand::spawnHabitat))
                .then(Commands.literal("tp")
                        .then(Commands.argument("site", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(SiteType.values()).map(s -> s.name().toLowerCase()), builder))
                                .executes(TwoFrogsCommand::teleportToSite)))
                .then(Commands.literal("debug")
                        .then(Commands.literal("create")
                                .executes(TwoFrogsCommand::create))
                        .then(Commands.literal("assign")
                                .then(Commands.literal("a")
                                        .executes(ctx -> assign(ctx, FrogRole.A)))
                                .then(Commands.literal("b")
                                        .executes(ctx -> assign(ctx, FrogRole.B))))
                        .then(Commands.literal("agitation")
                                .then(Commands.literal("a")
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 100.0))
                                                .executes(ctx -> setAgitation(ctx, FrogRole.A))))
                                .then(Commands.literal("b")
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 100.0))
                                                .executes(ctx -> setAgitation(ctx, FrogRole.B)))))
                        .then(Commands.literal("clear")
                                .executes(TwoFrogsCommand::clear))));
    }

    // ---------- Status ----------

    private static int status(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        TwoFrogsState state = TwoFrogsState.get(server);

        StringBuilder sb = new StringBuilder("--- The Two Frogs: status ---");
        for (FrogRole role : FrogRole.values()) {
            sb.append("\nFrog ").append(role).append(": ");
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                sb.append("not assigned");
                continue;
            }
            sb.append(uuid.get());
            sb.append("\n   behavior: ").append(state.getBehavior(role));
            sb.append("\n   agitation: ").append(String.format("%.2f", state.getAgitation(role)));
            sb.append("\n   escapes: ").append(state.getEscapeCount(role))
                    .append(", recaptures: ").append(state.getRecaptureCount(role));
            sb.append("\n   seconds away from habitat: ").append(state.getAwayTicks(role) / 20);
            sb.append("\n   now: ").append(describeLoaded(server, uuid.get()));
            FrogLocation lastKnown = state.getLastKnown(role);
            sb.append("\n   last known: ").append(lastKnown == null ? "none" : lastKnown);
            FrogLocation safe = state.getSafe(role);
            sb.append("\n   last safe spot: ").append(safe == null ? "none" : safe);
            sb.append("\n   inside habitat: ").append(state.isInHabitat(lastKnown));
        }
        sb.append("\nHabitat: ").append(
                state.getHabitatCenter().map(Object::toString).orElse("not set"));
        sb.append(" (radius ").append(state.getHabitatRadius()).append(")");
        sb.append("\nApocalypse triggered: ").append(state.isApocalypseTriggered());

        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static String describeLoaded(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) {
                return "loaded in " + level.dimension().location() + " at "
                        + entity.blockPosition().toShortString();
            }
        }
        return "not currently loaded";
    }

    // ---------- Planning commands ----------

    private static int plan(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> Component.literal("Planning... this can take a while."), false);
        SitePlanner.PlanResult result = SitePlanner.plan(server);
        if (result.success()) {
            ctx.getSource().sendSuccess(() -> Component.literal(result.message()), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(result.message()));
        return 0;
    }

    private static int planReset(CommandContext<CommandSourceStack> ctx) {
        WorldPlanState.get(ctx.getSource().getServer()).clear();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Plan cleared. Nothing already built or spawned was removed."), false);
        return 1;
    }

    private static int spawnHabitat(CommandContext<CommandSourceStack> ctx) {
        String message = HabitatSpawner.spawnFrogs(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int sites(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        WorldPlanState plan = WorldPlanState.get(server);
        ServerLevel level = server.overworld();

        if (!plan.isPlanned()) {
            ctx.getSource().sendFailure(Component.literal("Nothing is planned yet. Run /twofrogs plan."));
            return 0;
        }

        Optional<BlockPos> habitat = plan.getSite(SiteType.HABITAT);
        StringBuilder sb = new StringBuilder("--- Planned sites ---");
        for (SiteType type : SiteType.values()) {
            Optional<BlockPos> site = plan.getSite(type);
            sb.append("\n").append(type.name().toLowerCase()).append(": ");
            if (site.isEmpty()) {
                sb.append("none");
                continue;
            }
            BlockPos pos = site.get();
            String biome = level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("?");
            sb.append(pos.toShortString()).append("  ").append(biome);
            if (habitat.isPresent() && type != SiteType.HABITAT) {
                sb.append("  (").append(Math.round(SitePlanner.horizontalDistance(pos, habitat.get())))
                        .append(" from habitat)");
            }
            sb.append(plan.isBuilt(type) ? "  [built]" : "  [not built]");
        }
        Optional<BlockPos> altarA = plan.getSite(SiteType.ALTAR_A);
        Optional<BlockPos> altarB = plan.getSite(SiteType.ALTAR_B);
        if (altarA.isPresent() && altarB.isPresent()) {
            sb.append("\nAltar A to altar B: ")
                    .append(Math.round(SitePlanner.horizontalDistance(altarA.get(), altarB.get()))).append(" blocks");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int teleportToSite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "site");

        SiteType type;
        try {
            type = SiteType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Unknown site: " + name));
            return 0;
        }
        Optional<BlockPos> site = WorldPlanState.get(server).getSite(type);
        if (site.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("That site is not planned yet."));
            return 0;
        }

        ServerLevel level = server.overworld();
        BlockPos pos = site.get();
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4); // load (and generate) the area first
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        player.teleportTo(level, pos.getX() + 0.5, y, pos.getZ() + 0.5, player.getYRot(), player.getXRot());

        ctx.getSource().sendSuccess(() -> Component.literal("Teleported to " + type.name().toLowerCase() + "."), false);
        return 1;
    }

    // ---------- Debug commands ----------

    /** TEMPORARY test scaffolding: spawns both frogs next to the player and sets a habitat there. */
    private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        TwoFrogsState state = TwoFrogsState.get(server);
        ServerLevel level = player.serverLevel();

        if (level.dimension() != Level.OVERWORLD) {
            ctx.getSource().sendFailure(Component.literal("Run this in the Overworld."));
            return 0;
        }
        if (state.getFrogUuid(FrogRole.A).isPresent() || state.getFrogUuid(FrogRole.B).isPresent()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Frogs are already assigned. Run /twofrogs debug clear first."));
            return 0;
        }

        Frog a = spawnFrog(level, player.getX() + 2.0, player.getY(), player.getZ());
        Frog b = spawnFrog(level, player.getX() - 2.0, player.getY(), player.getZ());
        if (a == null || b == null) {
            ctx.getSource().sendFailure(Component.literal("Could not create the frogs."));
            return 0;
        }

        state.setFrogUuid(FrogRole.A, a.getUUID());
        state.setFrogUuid(FrogRole.B, b.getUUID());
        state.setHabitatCenter(player.blockPosition());
        state.setHabitatRadius(DEBUG_HABITAT_RADIUS);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Created Frog A and Frog B beside you. Habitat set here (radius "
                        + DEBUG_HABITAT_RADIUS + "). They look like any other frog."), false);
        return 1;
    }

    private static Frog spawnFrog(ServerLevel level, double x, double y, double z) {
        Frog frog = EntityType.FROG.create(level);
        if (frog == null) {
            return null;
        }
        frog.moveTo(x, y, z, level.random.nextFloat() * 360.0F, 0.0F);
        frog.finalizeSpawn(level, level.getCurrentDifficultyAt(frog.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        frog.setPersistenceRequired();
        level.addFreshEntity(frog);
        return frog;
    }

    private static int assign(CommandContext<CommandSourceStack> ctx, FrogRole role)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TwoFrogsState state = TwoFrogsState.get(ctx.getSource().getServer());

        List<Frog> frogs = player.serverLevel().getEntitiesOfClass(
                Frog.class, player.getBoundingBox().inflate(8.0));
        Optional<Frog> nearest = frogs.stream()
                .min(Comparator.comparingDouble(frog -> frog.distanceToSqr(player)));

        if (nearest.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No frog within 8 blocks of you."));
            return 0;
        }

        UUID uuid = nearest.get().getUUID();
        Optional<FrogRole> existing = state.getRole(uuid);
        if (existing.isPresent() && existing.get() != role) {
            ctx.getSource().sendFailure(Component.literal(
                    "That frog is already Frog " + existing.get() + "."));
            return 0;
        }

        state.setFrogUuid(role, uuid);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Assigned the nearest frog as Frog " + role + " (" + uuid + ")."), false);
        return 1;
    }

    private static int setAgitation(CommandContext<CommandSourceStack> ctx, FrogRole role) {
        TwoFrogsState state = TwoFrogsState.get(ctx.getSource().getServer());
        if (state.getFrogUuid(role).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Frog " + role + " is not assigned."));
            return 0;
        }
        double value = DoubleArgumentType.getDouble(ctx, "value");
        state.setAgitation(role, value);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set Frog " + role + " agitation to " + value + "."), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        TwoFrogsState.get(ctx.getSource().getServer()).clearFrogs();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared both frog assignments and all progress (the frog entities themselves stay in the world)."), false);
        return 1;
    }
}