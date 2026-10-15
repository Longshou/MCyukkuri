package io.github.longshou.mcyukkuri.event;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Admin test entries: /yukkuri homeclaim start | status. */
public final class HomeClaimCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("homeclaim")
                .then(Commands.literal("start").executes(context -> start(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource())));
    }
    private static int start(CommandSourceStack source) {
        var claim=HomeClaim.startAt(source.getLevel(),BlockPos.containing(source.getPosition()),source.getPlayer());
        if (claim==null) { source.sendFailure(Component.translatable("commands.mcyukkuri.homeclaim.failed")); return 0; }
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.homeclaim.started",claim.members.size()),true);
        return claim.members.size();
    }
    private static int status(CommandSourceStack source) {
        var lines=HomeClaim.status(source.getLevel());
        if (lines.isEmpty()) { source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.homeclaim.none"),false); return 0; }
        for (var line:lines) source.sendSuccess(() -> line,false);
        return lines.size();
    }
    private HomeClaimCommands() {}
}
