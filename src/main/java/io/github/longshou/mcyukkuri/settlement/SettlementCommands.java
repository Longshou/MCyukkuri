package io.github.longshou.mcyukkuri.settlement;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"wanderer", "family", "forest", "forest_led", "bandits"}, builder))
                        .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "kind"), BlockPos.containing(context.getSource().getPosition())))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "kind"), BlockPosArgument.getLoadedBlockPos(context, "pos"))))))
                .then(Commands.literal("info").executes(context -> info(context.getSource())))
                .then(Commands.literal("threat").then(Commands.argument("amount", IntegerArgumentType.integer(1, 20))
                        .executes(context -> threat(context.getSource(), IntegerArgumentType.getInteger(context, "amount")))))
                .then(Commands.literal("migrate").executes(context -> migrate(context.getSource())));
    }
    private static SettlementData.Group nearest(CommandSourceStack source) {
        var data = SettlementData.get(source.getLevel());
        BlockPos here = BlockPos.containing(source.getPosition());
        SettlementData.Group nearest = null;
        for (var group : data.groups()) if (nearest == null || group.center.distSqr(here) < nearest.center.distSqr(here)) nearest = group;
        return nearest;
    }
    /** Test aid: adds threat to the nearest community, as if scouts had seen a predator at the command position. */
    private static int threat(CommandSourceStack source, int amount) {
        var group = nearest(source);
        if (group == null) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.info.none")); return 0; }
        SettlementData.get(source.getLevel()).reportThreat(group, BlockPos.containing(source.getPosition()), source.getLevel().getGameTime(), amount);
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.threat", group.threat), true);
        return group.threat;
    }
    /** Test aid: the nearest community's loaded leader tries to move now (the site search still applies). */
    private static int migrate(CommandSourceStack source) {
        var group = nearest(source);
        if (group == null) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.info.none")); return 0; }
        if (group.leader == null || !(source.getLevel().getEntity(group.leader) instanceof io.github.longshou.mcyukkuri.entity.YukkuriEntity leader)) {
            source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.migrate.noleader")); return 0;
        }
        if (!Migration.start(leader, group, true)) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.migrate.fail")); return 0; }
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.migrate", group.migrateTo.toShortString()), true);
        return 1;
    }
    /** Reports the community nearest to the command position: leader, vacancy, member roles and threat count. */
    private static int info(CommandSourceStack source) {
        var level = source.getLevel();
        SettlementData.Group nearest = nearest(source);
        if (nearest == null) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.info.none")); return 0; }
        final SettlementData.Group group = nearest;
        int members = 0, outcast = 0, slaves = 0;
        for (var member : level.getEntitiesOfClass(io.github.longshou.mcyukkuri.entity.YukkuriEntity.class, new net.minecraft.world.phys.AABB(group.center).inflate(64),
                e -> e.isAlive() && group.id.equals(e.settlement().groupId()))) {
            members++;
            var role = group.role(member.getUUID());
            if (role == SettlementData.Role.OUTCAST) outcast++; else if (role == SettlementData.Role.UNUN_SLAVE) slaves++;
        }
        long vacant = group.vacant() && group.vacantSince > 0 ? level.getGameTime() - group.vacantSince : 0;
        final int loaded = members, cast = outcast, enslaved = slaves;
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.info", group.kind.name().toLowerCase(java.util.Locale.ROOT),
                group.center.toShortString(), group.leader == null ? "-" : group.leader.toString().substring(0, 8), group.led, vacant / 20,
                loaded, cast, enslaved, group.threat), false);
        return 1;
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
