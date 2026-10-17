package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Operator tools of the playground (试玩场): scenario starting conditions that need code, forced field clearing and the
 * status labels. Entities are spawned by the data pack with ordinary AI; these commands only set real state.
 */
public final class ScenarioCommands {
    /**
     * Failure text for the players standing near the scenario. A button runs these from a command block with output
     * off, so the source alone would swallow the reason.
     */
    static void tell(ServerLevel level, Vec3 at, String text, ChatFormatting colour) {
        var message = Component.literal(text).withStyle(colour);
        for (var player : level.players()) if (player.position().distanceToSqr(at) < 96.0 * 96.0) player.sendSystemMessage(message);
    }

    private static int refused(CommandSourceStack source, Entity at, String prefix, String reason) {
        tell(source.getLevel(), at.position(), "[试玩场] " + prefix + "没有成功：" + reason, ChatFormatting.RED);
        source.sendFailure(Component.literal(reason));
        return 0;
    }

    private static int done(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static YukkuriEntity yukkuri(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        return EntityArgument.getEntity(c, name) instanceof YukkuriEntity y ? y : null;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("scenario").requires(s -> s.hasPermission(2))
                .then(Commands.literal("wound").then(Commands.argument("targets", EntityArgument.entities()).executes(c -> {
                    // A real, small injury through the ordinary wound path.
                    int count = 0;
                    for (var entity : EntityArgument.getEntities(c, "targets"))
                        if (entity instanceof YukkuriEntity y && y.isAlive()) { y.body().injured(y.level().damageSources().generic(), 4f); count++; }
                    return count;
                })))
                .then(Commands.literal("bond").then(Commands.argument("first", EntityArgument.entity())
                        .then(Commands.argument("second", EntityArgument.entity()).executes(c -> {
                            var first = yukkuri(c, "first"); var second = yukkuri(c, "second");
                            if (first == null || second == null || first.level() != second.level()) return 0;
                            ScenarioSetup.bond(first, second);
                            return 1;
                        }))))
                .then(Commands.literal("mate").then(Commands.argument("first", EntityArgument.entity())
                        .then(Commands.argument("second", EntityArgument.entity()).executes(c -> {
                            var first = yukkuri(c, "first"); var second = yukkuri(c, "second");
                            if (first == null || second == null) return 0;
                            return first.family().introduce(second) ? 1 : refused(c.getSource(), first, "结为伴侣", "双方要是成年、同一维度、未亲缘相连，且没有别的伴侣。");
                        }))))
                .then(Commands.literal("kin").then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("child", EntityArgument.entity())
                                .then(Commands.argument("newborn", BoolArgumentType.bool()).executes(c -> {
                                    var parent = yukkuri(c, "parent"); var child = yukkuri(c, "child");
                                    if (parent == null || child == null) return 0;
                                    String reason = ScenarioSetup.kin(parent, child, BoolArgumentType.getBool(c, "newborn"));
                                    return reason == null ? 1 : refused(c.getSource(), parent, "建立亲子", reason);
                                })))))
                .then(Commands.literal("home").then(Commands.argument("actor", EntityArgument.entity())
                        .then(Commands.argument("home", BlockPosArgument.blockPos()).executes(c -> {
                            var actor = yukkuri(c, "actor");
                            if (actor == null) return 0;
                            if (!ScenarioSetup.home(actor, BlockPosArgument.getLoadedBlockPos(c, "home")))
                                return refused(c.getSource(), actor, "指定窝位", actor.getName().getString() + " 没能认下这个窝：窝位要有顶、够得着、没被别人占着。");
                            return 1;
                        }))))
                .then(Commands.literal("birth").then(Commands.argument("mother", EntityArgument.entity())
                        .then(Commands.argument("father", EntityArgument.entity())
                                .then(Commands.argument("outcome", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"natural", "caught", "missed"}, b))
                                        .executes(c -> {
                                            var mother = yukkuri(c, "mother"); var father = yukkuri(c, "father");
                                            if (mother == null || father == null) return 0;
                                            String outcome = StringArgumentType.getString(c, "outcome");
                                            String reason = ScenarioSetup.birth(mother, father, outcome);
                                            if (reason != null) return refused(c.getSource(), mother, "接生场景", reason);
                                            tell(c.getSource().getLevel(), mother.position(), "[试玩场] 母油已怀上真实胎次并进入临产（" + outcome
                                                    + "）；接下来的出生、接生和照护由普通规则发生。", ChatFormatting.GOLD);
                                            return 1;
                                        })))))
                .then(Commands.literal("boat").then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"sail", "shore", "low", "high", "voyage", "braid", "self", "birth"}, b))
                        .then(Commands.argument("parent", EntityArgument.entity())
                                .then(Commands.argument("others", EntityArgument.entities())
                                        .then(Commands.argument("bank", BlockPosArgument.blockPos())
                                                .then(Commands.argument("water", BlockPosArgument.blockPos()).executes(c -> {
                                                    var parent = yukkuri(c, "parent");
                                                    if (parent == null) return 0;
                                                    var others = new ArrayList<YukkuriEntity>();
                                                    for (var selected : EntityArgument.getEntities(c, "others")) {
                                                        if (selected == parent) continue;
                                                        if (selected instanceof YukkuriEntity other) others.add(other);
                                                    }
                                                    String mode = StringArgumentType.getString(c, "mode");
                                                    String reason = ScenarioBoats.prepare(mode, parent, others,
                                                            BlockPosArgument.getLoadedBlockPos(c, "bank"), BlockPosArgument.getLoadedBlockPos(c, "water"));
                                                    if (reason != null) return refused(c.getSource(), parent, "水上场景 " + mode, reason);
                                                    return done(c.getSource(), "水上场景 " + mode + " 已设置初始条件；之后由普通行为接管。");
                                                })))))))
                .then(Commands.literal("shore").then(Commands.argument("parent", EntityArgument.entity()).executes(c -> {
                    var parent = yukkuri(c, "parent");
                    if (parent == null) return 0;
                    return parent.aquaticLife().requestShoreVisit() ? 1
                            : refused(c.getSource(), parent, "返岸", "亲油现在不能返岸：要在自己的船上、船在水面、八格内有干岸、没有别的任务。");
                })))
                .then(Commands.literal("independence").then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("teen", EntityArgument.entity())
                                .then(Commands.argument("home", BlockPosArgument.blockPos()).executes(c -> {
                                    var parent = yukkuri(c, "parent"); var teen = yukkuri(c, "teen");
                                    if (parent == null || teen == null) return 0;
                                    String reason = ScenarioSetup.independence(parent, teen, BlockPosArgument.getLoadedBlockPos(c, "home"));
                                    if (reason != null) return refused(c.getSource(), parent, "独立场景", reason);
                                    tell(c.getSource().getLevel(), parent.position(), "[试玩场] 亚成已带着独立计划回到旧家：采叶、搬运、挖新巢，可能要十分钟以上（可用倍速）。",
                                            ChatFormatting.GOLD);
                                    return 1;
                                })))));
    }

    /** {@code /yukkuri clear <from> <to>}: removes the mod's entities and all dropped items in the box. Never refuses. */
    public static LiteralArgumentBuilder<CommandSourceStack> clear() {
        return Commands.literal("clear").requires(s -> s.hasPermission(2))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos()).executes(c -> {
                            int removed = PlaygroundTools.clear(c.getSource().getLevel(),
                                    BlockPosArgument.getLoadedBlockPos(c, "from"), BlockPosArgument.getLoadedBlockPos(c, "to"));
                            return done(c.getSource(), "已清除 " + removed + " 个实体。") * Math.max(1, removed);
                        })));
    }

    /** {@code /yukkuri observe on|off}: status labels over every nearby yukkuri (current behaviour, hunger, sleep, age, pregnancy, injury). */
    public static LiteralArgumentBuilder<CommandSourceStack> observe() {
        return Commands.literal("observe").requires(s -> s.hasPermission(2))
                .then(Commands.literal("on").executes(c -> {
                    PlaygroundTools.observe(true, c.getSource().getServer());
                    return done(c.getSource(), "观察标签已开启：附近油库里头顶显示 行为/动画 饥 困 年龄 孕 伤。");
                }))
                .then(Commands.literal("off").executes(c -> {
                    PlaygroundTools.observe(false, c.getSource().getServer());
                    return done(c.getSource(), "观察标签已关闭，名字已恢复。");
                }));
    }

    private ScenarioCommands() {}
}
