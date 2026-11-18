package com.twofrogs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.twofrogs.altar.AltarRitual;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.frog.Frog;

/** Debug commands: /twofrogs debug bring a|b and /twofrogs debug seal a|b. */
public final class AltarCommand {

    private AltarCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("twofrogs")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                        .then(Commands.literal("bring")
                                .then(Commands.literal("a").executes(ctx -> bring(ctx, FrogRole.A)))
                                .then(Commands.literal("b").executes(ctx -> bring(ctx, FrogRole.B))))
                        .then(Commands.literal("seal")
                                .then(Commands.literal("a").executes(ctx -> seal(ctx, FrogRole.A)))
                                .then(Commands.literal("b").executes(ctx -> seal(ctx, FrogRole.B))))));
    }

    private static int bring(CommandContext<CommandSourceStack> ctx, FrogRole role) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Frog frog = AltarRitual.findFrog(ctx.getSource().getServer(), role);
        if (frog == null) {
            ctx.getSource().sendFailure(Component.literal("Frog " + role + " is not loaded right now."));
            return 0;
        }
        if (frog.level() != player.level()) {
            ctx.getSource().sendFailure(Component.literal("Frog " + role + " is in another dimension."));
            return 0;
        }
        frog.moveTo(player.getX(), player.getY(), player.getZ(), frog.getYRot(), 0.0F);
        frog.setDeltaMovement(0.0, 0.0, 0.0);
        ctx.getSource().sendSuccess(() -> Component.literal("Frog " + role + " is now beside you."), false);
        return 1;
    }

    private static int seal(CommandContext<CommandSourceStack> ctx, FrogRole role) {
        String result = AltarRitual.forceSeal(ctx.getSource().getServer(), role);
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }
}