package io.github.longshou.mcyukkuri.body;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.family.RemainsStem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/**
 * {@code /yukkuri body core <remains>}: cut the centre filling out of remains without a player; {@code rot|soak <remains> <ticks>}: set the
 * decay or water clock (tests and the showcase); {@code bury <remains>}: shovel remains into a grave like a player standing at the
 * command's position and rotation (showcase start); {@code info <remains>}: read the stored cause category, form and clocks.
 */
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
                            context.getSource().sendFailure(Component.literal("#" + remains.getId() + ": no centre filling (" +
                                    (remains.data().getBoolean("CoreTaken") ? "taken" : "core hurt") + ")"));
                            continue;
                        }
                        remains.spawnAtLocation(core, .3f);
                        cut++;
                    }
                    int total = cut;
                    if (total > 0) context.getSource().sendSuccess(() -> Component.literal("cut " + total + " centre filling"), true);
                    return total;
                })))
                // Tests and the showcase: age remains to a given decay clock (24000 = one game day lying in the world).
                .then(Commands.literal("rot").then(Commands.argument("remains", EntityArgument.entities())
                        .then(Commands.argument("ticks", LongArgumentType.longArg(0)).executes(context -> {
                            int count = 0;
                            long ticks = LongArgumentType.getLong(context, "ticks");
                            for (var entity : EntityArgument.getEntities(context, "remains"))
                                if (entity instanceof RemainsEntity remains) { remains.setRot(ticks); count++; }
                            int total = count;
                            context.getSource().sendSuccess(() -> Component.literal("aged " + total + " remains to " + ticks), true);
                            return total;
                        }))))
                // Showcase starting point: the water clock of remains (6000 softened, 24000 dissolving); stages only move forward.
                .then(Commands.literal("soak").then(Commands.argument("remains", EntityArgument.entities())
                        .then(Commands.argument("ticks", LongArgumentType.longArg(0)).executes(context -> {
                            int count = 0;
                            long ticks = LongArgumentType.getLong(context, "ticks");
                            for (var entity : EntityArgument.getEntities(context, "remains"))
                                if (entity instanceof RemainsEntity remains) { remains.setSoak(ticks); count++; }
                            int total = count;
                            context.getSource().sendSuccess(() -> Component.literal("soaked " + total + " remains to " + ticks), true);
                            return total;
                        }))))
                // Showcase start: the same burial as a shovel, by whoever runs the command; the marker faces against its rotation.
                .then(Commands.literal("bury").then(Commands.argument("remains", EntityArgument.entities()).executes(context -> {
                    int count = 0;
                    var source = context.getSource();
                    for (var entity : EntityArgument.getEntities(context, "remains"))
                        if (entity instanceof RemainsEntity remains && remains.bury(source.getTextName(), Direction.fromYRot(source.getRotation().y))) count++;
                    int total = count;
                    if (total > 0) source.sendSuccess(() -> Component.literal("buried " + total + " remains"), true);
                    else source.sendFailure(Component.literal("no remains buried (they must lie on soil with room above)"));
                    return total;
                })))
                // Read-only: the stored cause, form and decay/water state of remains.
                .then(Commands.literal("info").then(Commands.argument("remains", EntityArgument.entities()).executes(context -> {
                    int count = 0;
                    for (var entity : EntityArgument.getEntities(context, "remains")) {
                        if (!(entity instanceof RemainsEntity remains)) continue;
                        var data = remains.data();
                        String category = RemainsCause.of(data);
                        String text = "#" + remains.getId() + " cause=" + (data.contains("Cause") ? data.getString("Cause") : "none")
                                + " internal=" + (data.contains(RemainsCause.INTERNAL) ? data.getString(RemainsCause.INTERNAL) : "none")
                                + " category=" + category + " form=" + RemainsCause.form(category)
                                + " rot=" + RemainsRot.id(remains.stage()) + "(" + remains.rot() + ")"
                                + " water=" + RemainsWater.id(remains.waterStage()) + "(" + remains.soak() + ")"
                                + " in_water=" + remains.isInWater()
                                + " food=" + CorpseFood.remaining(data) + "/" + CorpseFood.capacity(data) + " core=" + RemainsEntity.hasCore(data);
                        var stem=RemainsStem.visible(data);
                        context.getSource().sendSuccess(() -> Component.literal(stem==null?text:text+" stem="+stem.brood()
                                +" progress="+stem.progress()+" fruit="+stem.count()+" absent="+stem.absentMask()
                                +" folded="+data.getBoolean(RemainsStem.FOLDED)), false);
                        count++;
                    }
                    return count;
                })));
    }
}
