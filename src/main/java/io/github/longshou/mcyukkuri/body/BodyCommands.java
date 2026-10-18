package io.github.longshou.mcyukkuri.body;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/** {@code /yukkuri body core <remains>}: cut the centre filling out of remains without a player (tests and the showcase). */
public final class BodyCommands {
    private BodyCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("body")
                .then(Commands.literal("core").then(Commands.argument("remains", EntityArgument.entities()).executes(context -> {
                    int cut = 0;
                    for (var entity : EntityArgument.getEntities(context, "remains")) {
                        if (!(entity instanceof RemainsEntity remains)) continue;
                        var core = remains.cutCore();
                        if (core.isEmpty()) {
                            context.getSource().sendFailure(Component.literal("#" + remains.getId() + ": no centre filling (" + (remains.data().getBoolean("CoreTaken") ? "taken" : "core hurt") + ")"));
                            continue;
                        }
                        remains.spawnAtLocation(core, .3f);
                        cut++;
                    }
                    int total = cut;
                    if (total > 0) context.getSource().sendSuccess(() -> Component.literal("cut " + total + " centre filling"), true);
                    return total;
                })));
    }
}
