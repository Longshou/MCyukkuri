package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/** Explicit, reversible museum performance; ordinary pets cannot be selected. */
public final class BirthSceneCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("birth_scene").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stop").then(Commands.argument("mothers", EntityArgument.entities()).executes(c -> {
                    int count = 0;
                    for (var entity : EntityArgument.getEntities(c, "mothers"))
                        if (entity instanceof YukkuriEntity y && y.birthScene() != null) { y.birthScene().stop(); count++; }
                    return count;
                })))
                .then(Commands.literal("start")
                        .then(Commands.argument("mother", EntityArgument.entity())
                                .then(Commands.argument("helper", EntityArgument.entity())
                                        .then(Commands.argument("children", EntityArgument.entities())
                                                .then(Commands.argument("mode", StringArgumentType.word()).executes(c -> start(c)))))));
    }
    private static int start(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var first = EntityArgument.getEntity(c, "mother"); var second = EntityArgument.getEntity(c, "helper");
        var selected = EntityArgument.getEntities(c, "children"); String mode = StringArgumentType.getString(c, "mode");
        if (!(first instanceof YukkuriEntity mother) || !(second instanceof YukkuriEntity helper)
                || mother == helper || mother.level() != helper.level() || mother.level() != c.getSource().getLevel()
                || helper.getVariant() != YukkuriVariant.MARISA
                || !mother.family().adult() || !helper.family().adult() || !mother.subspecies().canReproduce()
                || !helper.life().hasAccessory() || selected.isEmpty() || selected.size() > 5
                || !java.util.Set.of("land", "bank", "boat").contains(mode)) return 0;
        if (mother.birthScene() != null) mother.birthScene().stop();
        if (!BirthScene.eligible(mother) || !BirthScene.eligible(helper) || helper.birthScene() != null) return 0;
        var children = new java.util.ArrayList<YukkuriEntity>();
        for (var entity : selected) {
            if (!(entity instanceof YukkuriEntity y) || !BirthScene.eligible(y) || y.family().adult()
                    || y.birthScene() != null || y.level() != mother.level()) return 0;
            children.add(y);
        }
        children.sort(java.util.Comparator.comparing(YukkuriEntity::getUUID));
        new BirthScene(mother, helper, children, mode);
        c.getSource().sendSuccess(() -> Component.literal("出生演出开始：靠近接生、阵痛、逐只产出与产后照料。"), false);
        return 1;
    }
    private BirthSceneCommands() {}
}
