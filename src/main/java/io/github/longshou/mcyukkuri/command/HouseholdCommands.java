package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.longshou.mcyukkuri.house.HouseholdEcology;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Operator tools for the household ecology (/yukkuri household ...). {@code activity} stands in for people living in the
 * room around the executor: the same lint, crumbs and move-in rules as a real stay run at once. {@code status} prints the
 * debris, the activity record, every resident's stage and every nest within reach, for people and for automated checks.
 */
public final class HouseholdCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("household")
                .then(Commands.literal("status").executes(HouseholdCommands::status))
                .then(Commands.literal("activity")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(200, 240000))
                                .executes(context -> activity(context, false, false))
                                .then(Commands.argument("sleeping", BoolArgumentType.bool())
                                        .executes(context -> activity(context, BoolArgumentType.getBool(context, "sleeping"), false))
                                        .then(Commands.argument("force", BoolArgumentType.bool())
                                                .executes(context -> activity(context, BoolArgumentType.getBool(context, "sleeping"),
                                                        BoolArgumentType.getBool(context, "force")))))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var lines = HouseholdEcology.report(source.getLevel(), BlockPos.containing(source.getPosition()));
        for (var line : lines) source.sendSuccess(() -> Component.literal(line), false);
        return lines.size();
    }

    private static int activity(CommandContext<CommandSourceStack> context, boolean sleeping, boolean force) {
        var source = context.getSource();
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        String result = HouseholdEcology.simulate(source.getLevel(), BlockPos.containing(source.getPosition()), ticks, sleeping, force);
        source.sendSuccess(() -> Component.literal("household activity " + ticks + " ticks: " + result), true);
        return 1;
    }

    private HouseholdCommands() {}
}
