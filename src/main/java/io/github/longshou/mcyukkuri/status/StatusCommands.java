package io.github.longshou.mcyukkuri.status;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;

/**
 * {@code /yukkuri status <targets>}: the whole register in chat and log.
 * {@code ... raw}: the same as one SNBT compound. {@code status set <targets> <key> <value>}: controlled write.
 * {@code status relate <from> <to> <score>}: set an acquaintance score.
 * {@code status trace off|light|dense}: structured observation; {@code trace window <scene> [field] [x1,y1,z1,x2,y2,z2]} starts a scene window.
 * {@code status seed <n>}: the actor seed of the run that starts next.
 */
public final class StatusCommands {
    private static final DynamicCommandExceptionType INVALID_BOUNDS = new DynamicCommandExceptionType(
            value -> Component.literal("trace window bounds must be x1,y1,z1,x2,y2,z2: " + value));
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("status")
                .then(Commands.literal("trace")
                        .then(Commands.literal("window").then(Commands.argument("scene", StringArgumentType.word())
                                .executes(context -> window(context, null))
                                .then(Commands.argument("field", StringArgumentType.word())
                                        .executes(context -> window(context, StringArgumentType.getString(context, "field"), null))
                                        .then(Commands.argument("bounds", StringArgumentType.greedyString())
                                                .executes(context -> window(context, StringArgumentType.getString(context, "field"),
                                                        StringArgumentType.getString(context, "bounds")))))))
                        .then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
                            String id = StringArgumentType.getString(context, "mode");
                            TraceRecorder.Mode mode = TraceRecorder.Mode.parse(id);
                            TraceRecorder.setMode(mode);
                            context.getSource().sendSuccess(() -> Component.literal("status trace " + mode.id), true);
                            return mode == TraceRecorder.Mode.OFF ? 0 : 1;
                        })))
                .then(Commands.literal("seed").then(Commands.argument("seed", LongArgumentType.longArg()).executes(context -> {
                    long seed = LongArgumentType.getLong(context, "seed");
                    TraceRecorder.setRunSeed(context.getSource().getLevel(), seed);
                    context.getSource().sendSuccess(() -> Component.literal("status seed " + seed), true);
                    return 1;
                })))
                .then(Commands.literal("set").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    var ids = new ArrayList<String>();
                                    for (var key : StatusKey.values()) if (key.writable()) ids.add(key.id());
                                    return SharedSuggestionProvider.suggest(ids, builder);
                                })
                                .then(Commands.argument("value", StringArgumentType.greedyString()).executes(StatusCommands::set)))))
                .then(Commands.literal("relate")
                        .then(Commands.argument("from", EntityArgument.entity()).then(Commands.argument("to", EntityArgument.entity())
                                .then(Commands.argument("score", IntegerArgumentType.integer(-100, 100)).executes(context -> {
                                    var from = EntityArgument.getEntity(context, "from");
                                    var to = EntityArgument.getEntity(context, "to");
                                    if (!(from instanceof YukkuriEntity a)) {
                                        context.getSource().sendFailure(Component.literal("source must be yukkuri"));
                                        return 0;
                                    }
                                    YukkuriStatus.relate(a, to, IntegerArgumentType.getInteger(context, "score"));
                                    return 1;
                                })))))
                .then(Commands.literal("fear")
                        .then(Commands.argument("from", EntityArgument.entity()).then(Commands.argument("to", EntityArgument.entity())
                                .then(Commands.argument("score", IntegerArgumentType.integer(0, 100)).executes(context -> {
                                    var from = EntityArgument.getEntity(context, "from");
                                    var to = EntityArgument.getEntity(context, "to");
                                    if (!(from instanceof YukkuriEntity a)) return 0;
                                    var data = YukkuriWorldData.get(context.getSource().getLevel());
                                    a.life().personality().relationshipChanged(to.getUUID(), 0,
                                            IntegerArgumentType.getInteger(context, "score") - data.fear(a.getUUID(), to.getUUID()));
                                    return 1;
                                })))))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(context -> show(context, false))
                        .then(Commands.literal("raw").executes(context -> show(context, true))));
    }

    private static int window(CommandContext<CommandSourceStack> context, String field) throws CommandSyntaxException {
        return window(context, field, null);
    }

    private static int window(CommandContext<CommandSourceStack> context, String field, String bounds) throws CommandSyntaxException {
        String scene = StringArgumentType.getString(context, "scene");
        TraceRecorder.window(context.getSource().getLevel(), scene, field, bounds(bounds));
        context.getSource().sendSuccess(() -> Component.literal("status trace window " + scene), false);
        return 1;
    }

    private static AABB bounds(String text) throws CommandSyntaxException {
        if (text == null) return null;
        String[] parts = text.split(",", -1);
        if (parts.length != 6) throw INVALID_BOUNDS.create(text);
        double[] values = new double[6];
        try {
            for (int i = 0; i < values.length; i++) {
                values[i] = Double.parseDouble(parts[i]);
                if (!Double.isFinite(values[i])) throw INVALID_BOUNDS.create(text);
            }
        } catch (NumberFormatException error) {
            throw INVALID_BOUNDS.create(text);
        }
        if (values[0] > values[3] || values[1] > values[4] || values[2] > values[5]) throw INVALID_BOUNDS.create(text);
        return new AABB(values[0], values[1], values[2], values[3], values[4], values[5]);
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
