package io.github.longshou.mcyukkuri.status;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * {@code /yukkuri status <targets>}: the whole register in chat and log.
 * {@code ... raw}: the same as one SNBT compound. {@code status set <targets> <key> <value>}: controlled write.
 * {@code status relate <from> <to> <score>}: set an acquaintance score. {@code status trace <on|off>}: log changes.
 */
public final class StatusCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("status")
                .then(Commands.literal("trace").then(Commands.argument("on", BoolArgumentType.bool()).executes(context -> {
                    boolean on = BoolArgumentType.getBool(context, "on");
                    StatusEvents.trace(on);
                    context.getSource().sendSuccess(() -> Component.literal("status trace " + (on ? "on" : "off")), true);
                    return on ? 1 : 0;
                })))
                .then(Commands.literal("set").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    var ids = new java.util.ArrayList<String>();
                                    for (var key : StatusKey.values()) if (key.writable()) ids.add(key.id());
                                    return SharedSuggestionProvider.suggest(ids, builder);
                                })
                                .then(Commands.argument("value", StringArgumentType.greedyString()).executes(StatusCommands::set)))))
                .then(Commands.literal("relate")
                        .then(Commands.argument("from", EntityArgument.entity()).then(Commands.argument("to", EntityArgument.entity())
                                .then(Commands.argument("score", IntegerArgumentType.integer(-100, 100)).executes(context -> {
                                    var from = EntityArgument.getEntity(context, "from");
                                    var to = EntityArgument.getEntity(context, "to");
                                    if (!(from instanceof YukkuriEntity a) || !(to instanceof YukkuriEntity b)) {
                                        context.getSource().sendFailure(Component.literal("both ends must be yukkuri"));
                                        return 0;
                                    }
                                    YukkuriStatus.relate(a, b, IntegerArgumentType.getInteger(context, "score"));
                                    return 1;
                                })))))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> show(context, false))
                        .then(Commands.literal("raw").executes(context -> show(context, true))));
    }

    private static int show(CommandContext<CommandSourceStack> context, boolean raw) throws CommandSyntaxException {
        int count = 0;
        for (Entity target : EntityArgument.getEntities(context, "targets")) {
            if (!(target instanceof YukkuriEntity y)) continue;
            count++;
            if (raw) {
                String text = y.getType().getDescriptionId() + " #" + y.getId() + " " + YukkuriStatus.snapshot(y);
                context.getSource().sendSuccess(() -> Component.literal(text), false);
            } else for (String line : YukkuriStatus.describe(y)) context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        if (count == 0) context.getSource().sendFailure(Component.literal("no yukkuri among the targets"));
        return count;
    }

    private static int set(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String key = StringArgumentType.getString(context, "key"), value = StringArgumentType.getString(context, "value");
        int done = 0;
        for (Entity target : EntityArgument.getEntities(context, "targets")) {
            if (!(target instanceof YukkuriEntity y)) continue;
            String refusal = YukkuriStatus.set(y, key, value);
            if (refusal == null) done++;
            else context.getSource().sendFailure(Component.literal("#" + y.getId() + " " + key + ": " + refusal));
        }
        int applied = done;
        context.getSource().sendSuccess(() -> Component.literal("status set " + key + "=" + value + " on " + applied), true);
        return done;
    }

    private StatusCommands() {}
}
