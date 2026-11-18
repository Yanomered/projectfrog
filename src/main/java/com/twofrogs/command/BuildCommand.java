package com.twofrogs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.twofrogs.state.WorldPlanState.SiteType;
import com.twofrogs.world.structure.StructureBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Arrays;

/** Debug command: /twofrogs build <site> [force]. */
public final class BuildCommand {

    private BuildCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("twofrogs")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("build")
                        .then(Commands.argument("site", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(SiteType.values()).map(s -> s.name().toLowerCase()), builder))
                                .executes(ctx -> build(ctx, false))
                                .then(Commands.literal("force")
                                        .executes(ctx -> build(ctx, true))))));
    }

    private static int build(CommandContext<CommandSourceStack> ctx, boolean force) {
        String name = StringArgumentType.getString(ctx, "site");
        SiteType type;
        try {
            type = SiteType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Unknown site: " + name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Building " + name + "..."), false);
        String result = StructureBuilder.build(ctx.getSource().getServer(), type, force);
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }
}