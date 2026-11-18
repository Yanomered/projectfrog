package com.twofrogs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.twofrogs.clue.ClueGenerator;
import com.twofrogs.clue.ClueRecovery;
import com.twofrogs.clue.ClueState;
import com.twofrogs.state.WorldPlanState.SiteType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Debug commands: /twofrogs clue show|give <site>, /twofrogs clue recover, /twofrogs clue regenerate. */
public final class ClueCommand {

    private ClueCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("twofrogs")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("clue")
                        .then(Commands.literal("show")
                                .then(Commands.argument("site", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                ClueGenerator.CLUE_SITES.stream().map(s -> s.name().toLowerCase()), builder))
                                        .executes(ClueCommand::show)))
                        .then(Commands.literal("give")
                                .then(Commands.argument("site", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                ClueGenerator.CLUE_SITES.stream().map(s -> s.name().toLowerCase()), builder))
                                        .executes(ClueCommand::give)))
                        .then(Commands.literal("recover").executes(ctx -> {
                            int count = ClueRecovery.runNow(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Restored " + count + " clue book(s). (You must be within 32 blocks of a site.)"), false);
                            return 1;
                        }))
                        .then(Commands.literal("regenerate").executes(ctx -> {
                            ClueGenerator.regenerateAll(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "All clues were written again and put back into their containers."), false);
                            return 1;
                        }))));
    }

    private static SiteType parse(CommandContext<CommandSourceStack> ctx) {
        try {
            SiteType type = SiteType.valueOf(StringArgumentType.getString(ctx, "site").toUpperCase());
            return ClueGenerator.CLUE_SITES.contains(type) ? type : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        SiteType type = parse(ctx);
        ClueState clues = ClueState.get(ctx.getSource().getServer());
        if (type == null || !clues.isGenerated(type)) {
            ctx.getSource().sendFailure(Component.literal("No clue has been written for that site yet."));
            return 0;
        }
        StringBuilder sb = new StringBuilder(clues.getTitle(type));
        int number = 1;
        for (String page : clues.getPages(type)) {
            sb.append("\n--- page ").append(number++).append(" ---\n").append(page);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SiteType type = parse(ctx);
        ClueState clues = ClueState.get(ctx.getSource().getServer());
        if (type == null || !clues.isGenerated(type)) {
            ctx.getSource().sendFailure(Component.literal("No clue has been written for that site yet."));
            return 0;
        }
        ItemStack book = ClueGenerator.makeBook(clues, type);
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Gave you the clue book."), false);
        return 1;
    }
}