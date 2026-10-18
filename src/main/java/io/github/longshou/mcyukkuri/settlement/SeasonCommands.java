package io.github.longshou.mcyukkuri.settlement;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Operator report of the season the biome at a position resolves to. */
public final class SeasonCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("season")
                .executes(context -> report(context.getSource(), BlockPos.containing(context.getSource().getPosition())))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> report(context.getSource(), BlockPosArgument.getLoadedBlockPos(context, "pos"))));
    }
    private static int report(CommandSourceStack source, BlockPos pos) {
        var level = source.getLevel();
        Season season = Seasons.at(level, pos);
        String biome = level.getBiome(pos).unwrapKey().map(key -> key.location().toString()).orElse("?");
        Component off = Seasons.enabled() ? Component.empty() : Component.translatable("commands.mcyukkuri.season.off");
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.season.report", pos.toShortString(),
                Component.translatable("season.mcyukkuri." + season.id()), biome, off), false);
        return season.ordinal() + 1;
    }
    private SeasonCommands() {}
}
