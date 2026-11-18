package com.twofrogs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.twofrogs.encounter.Carriers;
import com.twofrogs.encounter.EncounterDirector;
import com.twofrogs.encounter.EncounterSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Controls for the journey encounters: /twofrogs encounters ... */
public final class EncounterCommand {

    private static final List<String> KINDS = List.of("patrol", "ambush", "water", "croak", "storm", "camp");

    private EncounterCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("twofrogs")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("encounters")
                        .then(Commands.literal("status").executes(ctx -> {
                            String text = EncounterDirector.status(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                            return 1;
                        }))
                        .then(Commands.literal("on").executes(ctx -> {
                            EncounterSettings.get(ctx.getSource().getServer()).setEnabled(true);
                            ctx.getSource().sendSuccess(() -> Component.literal("Encounters are on."), false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            EncounterSettings.get(ctx.getSource().getServer()).setEnabled(false);
                            ctx.getSource().sendSuccess(() -> Component.literal("Encounters are off."), false);
                            return 1;
                        }))
                        .then(Commands.literal("frequency")
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 3.0))
                                        .executes(ctx -> {
                                            double value = DoubleArgumentType.getDouble(ctx, "value");
                                            EncounterSettings.get(ctx.getSource().getServer()).setFrequency(value);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Encounter frequency is now " + value + " (1.0 is normal)."), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("difficulty")
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 2.0))
                                        .executes(ctx -> {
                                            double value = DoubleArgumentType.getDouble(ctx, "value");
                                            EncounterSettings.get(ctx.getSource().getServer()).setDifficulty(value);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Encounter difficulty is now " + value + " (1.0 is normal)."), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("progress")
                                .then(Commands.literal("off").executes(ctx -> {
                                    Carriers.progressOverride = -1.0;
                                    ctx.getSource().sendSuccess(() -> Component.literal("Journey progress is real again."), false);
                                    return 1;
                                }))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
                                        .executes(ctx -> {
                                            double value = DoubleArgumentType.getDouble(ctx, "value");
                                            Carriers.progressOverride = value;
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Pretending the journey is " + Math.round(value * 100) + "% along."), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("trigger")
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KINDS, builder))
                                        .executes(ctx -> trigger(ctx.getSource(), StringArgumentType.getString(ctx, "kind")))))));
    }

    private static int trigger(CommandSourceStack source, String kind) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String result = EncounterDirector.trigger(source.getServer(), player, kind);
        source.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }
}