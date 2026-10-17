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
                                                }).executes(BirthSceneCommands::deliver)))));
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
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("真实胎次 " + result.brood().id + " 已提前临产（"
                + result.brood().children.size() + " 只，" + outcome
                + "）；双亲启用正常 AI，出生与接生遵守实际碰撞。caught 仍可能因障碍失接，missed 会触发现有致命漏接后果。"), false);
        return 1;
    }

    private BirthSceneCommands() {}
}
