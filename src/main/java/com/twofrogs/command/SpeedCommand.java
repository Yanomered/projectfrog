package com.twofrogs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.twofrogs.frog.SpeedProbe;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Debug command: /twofrogs debug speed. */
public final class SpeedCommand {

    private SpeedCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("twofrogs")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                        .then(Commands.literal("speed").executes(ctx -> {
                            String report = SpeedProbe.report(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(report), false);
                            return 1;
                        }))));
    }
}