package io.github.longshou.mcyukkuri.settlement;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class SettlementCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("settlement").then(Commands.literal("spawn")
                .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"wanderer", "family", "forest", "forest_led"}, builder))
                        .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "kind"), BlockPos.containing(context.getSource().getPosition())))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "kind"), BlockPosArgument.getLoadedBlockPos(context, "pos"))))));
    }
    private static int spawn(CommandSourceStack source, String kind, BlockPos pos) {
        SettlementSpawner.Preset preset;
        try { preset = SettlementSpawner.Preset.valueOf(kind.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.unknown")); return 0; }
        var members = SettlementSpawner.spawn(source.getLevel(), pos, preset);
        if (members.isEmpty()) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.blocked")); return 0; }
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.spawned", members.size()), true);
        return members.size();
    }
    private SettlementCommands() {}
}
