package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/** Explicit operator trigger for normal conception, delivery and postnatal care. */
public final class BirthSceneCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("birth_scene").requires(s -> s.hasPermission(2))
                .then(Commands.literal("deliver")
                        .then(Commands.argument("mother", EntityArgument.entity())
                                .then(Commands.argument("father", EntityArgument.entity())
                                        .then(Commands.argument("outcome", StringArgumentType.word())
                                                .suggests((c, builder) -> {
                                                    BirthScene.OUTCOMES.stream().sorted().forEach(builder::suggest);
                                                    return builder.buildFuture();
                                                }).executes(BirthSceneCommands::deliver)))))
                // Retain parsable old calls with an explicit migration message. Existing selected
                // children are not templates and are never hidden, cloned, moved or discarded.
                .then(Commands.literal("start")
                        .then(Commands.argument("mother", EntityArgument.entity())
                                .then(Commands.argument("helper", EntityArgument.entity())
                                        .then(Commands.argument("children", EntityArgument.entities())
                                                .then(Commands.argument("mode", StringArgumentType.word())
                                                        .executes(BirthSceneCommands::legacy))))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("mothers", EntityArgument.entities()).executes(c -> {
                            c.getSource().sendFailure(Component.literal("真实出生不能倒放；本命令不会取消胎次、删除幼体或收走帽子。"));
                            return 0;
                        })));
    }

    private static int deliver(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var first = EntityArgument.getEntity(c, "mother");
        var second = EntityArgument.getEntity(c, "father");
        if (!(first instanceof YukkuriEntity mother) || !(second instanceof YukkuriEntity father)
                || mother.level() != c.getSource().getLevel() || father.level() != c.getSource().getLevel()) {
            c.getSource().sendFailure(Component.literal("请选择当前维度的真实母油与父油。"));
            return 0;
        }
        String outcome = StringArgumentType.getString(c, "outcome");
        var result = BirthScene.deliver(mother, father, outcome);
        if (result.refusal() != null) {
            c.getSource().sendFailure(Component.literal(result.refusal()));
            // Run from a button function the command source is silent: tell the players standing by why nothing started.
            var note = Component.literal("[接生] 未开始：" + result.refusal()).withStyle(net.minecraft.ChatFormatting.RED);
            if (mother.level() instanceof net.minecraft.server.level.ServerLevel level)
                for (var player : level.players()) if (player.distanceToSqr(mother) < 48 * 48) player.sendSystemMessage(note);
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("真实胎次 " + result.brood().id + " 已提前临产（"
                + result.brood().children.size() + " 只，" + outcome
                + "）；双亲启用正常 AI，出生与接生遵守实际碰撞。caught 仍可能因障碍失接；missed 为无垫落地磕伤（不致命）；overstrain 为帽已就位但被推过帽沿的致命旧分支。"), false);
        return 1;
    }

    private static int legacy(CommandContext<CommandSourceStack> c) {
        c.getSource().sendFailure(Component.literal("旧模板幼体演出已停用，所选实体未改变。请改用 /yukkuri birth_scene deliver <mother> <father> <natural|caught|missed>；先准备真实窝位或帽船，不再预放出生替身。"));
        return 0;
    }
    private BirthSceneCommands() {}
}
