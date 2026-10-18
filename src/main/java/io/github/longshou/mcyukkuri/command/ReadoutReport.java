package io.github.longshou.mcyukkuri.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.longshou.mcyukkuri.network.ReadoutNetworking;
import io.github.longshou.mcyukkuri.network.ReadoutPayload;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Captures exactly one synchronous command's feedback for the executing player's read-only report. */
final class ReadoutReport {
    static int open(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var player = source.getPlayerOrException();
        String command = StringArgumentType.getString(context, "command");
        var messages = new JsonArray();
        // Store serialized components immediately: command-owned mutable components cannot change the snapshot later.
        class Capture implements CommandSource {
            int chars = 2;
            boolean tooLarge;
            public void sendSystemMessage(Component message) {
                if (tooLarge) return;
                String json = Component.Serializer.toJson(message, source.registryAccess());
                if (json.length() + 1L > ReadoutPayload.MAX_REPORT_CHARS - chars) { tooLarge = true; return; }
                chars += json.length() + 1;
                messages.add(JsonParser.parseString(json));
            }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
        }
        var capture = new Capture();
        capture.sendSystemMessage(Component.literal("试玩场读数快照"));
        capture.sendSystemMessage(Component.literal("观察者：" + player.getGameProfile().getName()
                + "\n维度：" + source.getLevel().dimension().location()
                + "\n位置：" + net.minecraft.core.BlockPos.containing(source.getPosition()).toShortString()
                + "\n游戏 tick：" + source.getLevel().getGameTime()));
        capture.sendSystemMessage(Component.literal("命令：/" + command));
        var reading = new CommandSourceStack(capture, source.getPosition(), source.getRotation(), source.getLevel(),
                2, "playground", Component.literal("playground"), source.getServer(), player);
        source.getServer().getCommands().performPrefixedCommand(reading, command);
        if (capture.tooLarge || !ReadoutNetworking.send(player, messages.toString())) {
            player.sendSystemMessage(Component.literal("读数报告过大，未显示不完整内容；请缩小目标范围后重试。"));
            return 0;
        }
        return 1;
    }

    private ReadoutReport() {}
}
