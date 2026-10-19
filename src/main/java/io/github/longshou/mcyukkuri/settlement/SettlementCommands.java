package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.settlement.site.SiteBuilder;
import io.github.longshou.mcyukkuri.settlement.site.SitePlanner;
import io.github.longshou.mcyukkuri.settlement.site.SiteTier;
import io.github.longshou.mcyukkuri.settlement.site.SiteWorld;
import java.util.Locale;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

public final class SettlementCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("settlement").then(Commands.literal("spawn")
                .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"wanderer", "family", "forest", "forest_led", "bandits",
                                "band", "village", "village_led", "village_unled", "demo"}, builder))
                        .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "kind"), BlockPos.containing(context.getSource().getPosition())))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "kind"), BlockPosArgument.getLoadedBlockPos(context, "pos"))))))
                .then(Commands.literal("info").executes(context -> info(context.getSource())))
                .then(Commands.literal("site")
                        .then(Commands.literal("info").executes(context -> siteInfo(context.getSource())))
                        .then(Commands.literal("vacant")
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"band", "village", "demo"}, builder))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(context -> vacantSite(context.getSource(), StringArgumentType.getString(context, "kind"), BlockPosArgument.getLoadedBlockPos(context, "pos"))))))
                        .then(Commands.literal("check").executes(context -> siteCheck(context.getSource()))))
                .then(Commands.literal("migrate").executes(context -> migrate(context.getSource())))
                .then(CommonsCommands.command());
    }
    private static SettlementData.Group nearest(CommandSourceStack source) {
        var data = SettlementData.get(source.getLevel());
        BlockPos here = BlockPos.containing(source.getPosition());
        SettlementData.Group nearest = null;
        for (var group : data.groups()) if (nearest == null || group.center.distSqr(here) < nearest.center.distSqr(here)) nearest = group;
        return nearest;
    }
    /** Test aid: the nearest community's loaded leader tries to move now (the site search still applies). */
    private static int migrate(CommandSourceStack source) {
        var group = nearest(source);
        if (group == null) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.info.none")); return 0; }
        if (group.leader == null || !(source.getLevel().getEntity(group.leader) instanceof YukkuriEntity leader)) {
            source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.migrate.noleader")); return 0;
        }
        var result=Migration.start(leader, group, true);
        if(result==Result.DEFERRED) {
            source.sendSuccess(()->Component.literal("Settlement search queued; the leader will continue shortly."),false);return 0;
        }
        if (result==Result.NOT_FOUND) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.migrate.fail")); return 0; }
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
        for (var member : LoadedGroupMembers.query(level, group.id, new AABB(group.center).inflate(64),
                e -> e.isAlive() && group.id.equals(e.settlement().groupId()))) {
            members++;
            var role = group.role(member.getUUID());
            if (role == SettlementData.Role.OUTCAST) outcast++; else if (role == SettlementData.Role.UNUN_SLAVE) slaves++;
        }
        long vacant = group.vacant() && group.vacantSince > 0 ? level.getGameTime() - group.vacantSince : 0;
        final int loaded = members, cast = outcast, enslaved = slaves;
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.info", group.kind.name().toLowerCase(Locale.ROOT),
                group.center.toShortString(), group.leader == null ? "-" : group.leader.toString().substring(0, 8), group.led, vacant / 20,
                loaded, cast, enslaved, group.threat), false);
        return 1;
    }
    private static SettlementData.Site nearestSite(CommandSourceStack source) {
        SettlementData.Site nearest = null;
        BlockPos here = BlockPos.containing(source.getPosition());
        for (var site : SettlementData.get(source.getLevel()).sites())
            if (nearest == null || site.origin.distSqr(here) < nearest.origin.distSqr(here)) nearest = site;
        return nearest;
    }
    private static int siteInfo(CommandSourceStack source) {
        var site = nearestSite(source);
        if (site == null) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.site.none")); return 0; }
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.site.info", site.tier.name().toLowerCase(Locale.ROOT),
                site.origin.toShortString(), site.state.name().toLowerCase(Locale.ROOT), site.prosperity,
                site.group == null ? "-" : site.group.toString().substring(0, 8)), false);
        return 1;
    }
    private static int siteCheck(CommandSourceStack source) {
        var site = nearestSite(source);
        if (site == null || site.plan() == null) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.site.none")); return 0; }
        var bad = SiteBuilder.validate(source.getLevel(), site.plan());
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.site.check", bad == null ? "ok" : bad), false);
        return bad == null ? 1 : 0;
    }
    /** Builds a whole planned site on the ground around {@code pos} and moves a fitting group in. */
    private static int spawnSite(CommandSourceStack source, SiteTier tier, BlockPos pos) {
        return spawnSite(source, tier, pos, null);
    }
    /** {@code led}: null rolls the configured leader chance; true/false forces a village with or without a leader (tests). */
    private static int spawnSite(CommandSourceStack source, SiteTier tier, BlockPos pos, Boolean led) {
        return spawnSite(source, tier, pos, led, true);
    }
    /** Build real vacant homes for migration or later settlement, using the ordinary site planner and builder. */
    private static int vacantSite(CommandSourceStack source, String kind, BlockPos pos) {
        var tier = SiteTier.byName(kind);
        if (tier == null) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.unknown")); return 0; }
        return spawnSite(source, tier, pos, null, false);
    }
    private static int spawnSite(CommandSourceStack source, SiteTier tier, BlockPos pos, Boolean led, boolean populate) {
        var level = source.getLevel();
        var terrain = new SiteBuilder.LevelTerrain(level);
        var plan = SitePlanner.plan(tier, level.random.nextLong(), level.random.nextInt(4), pos.getX(), pos.getZ(),
                SiteWorld.palette(level.getBiome(pos)), terrain, false);
        if (plan == null) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.site.fail", SitePlanner.lastFailure())); return 0; }
        if (!SiteBuilder.loaded(level, plan)) {
            source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.site.fail", "the area is not loaded")); return 0;
        }
        var clip = new BoundingBox(plan.minX, level.getMinBuildHeight(), plan.minZ, plan.maxX, level.getMaxBuildHeight() - 1, plan.maxZ);
        var result = SiteBuilder.build(level, plan, clip, terrain, true, false);
        var site = SettlementData.get(level).addSite(plan, false);
        boolean leader = populate && (led != null ? led : level.random.nextInt(100) < YukkuriConfig.LEADER_CHANCE.get());
        SiteLife.clearLanes(level, site);
        if (!populate) {
            source.sendSuccess(() -> Component.literal("Vacant " + tier.name().toLowerCase(Locale.ROOT) + " site at " + pos.toShortString()
                    + " (" + result.blocks() + " blocks)"), true);
            return 1;
        }
        var members = SettlementSpawner.spawnAtSite(level, site, SettlementComposition.forTier(tier, level.random, leader), true);
        String name = tier.name().toLowerCase(Locale.ROOT);
        if (members.isEmpty()) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.site.empty", name, result.blocks(), SettlementSpawner.lastFailure())); return 0; }
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.site.built", name, result.blocks(), members.size()), true);
        return members.size();
    }
    private static int spawn(CommandSourceStack source, String kind, BlockPos pos) {
        var tier = SiteTier.byName(kind);
        if (tier != null) return spawnSite(source, tier, pos);
        if ("village_led".equals(kind) || "village_unled".equals(kind))
            return spawnSite(source, SiteTier.VILLAGE, pos, "village_led".equals(kind));
        SettlementSpawner.Preset preset;
        try { preset = SettlementSpawner.Preset.valueOf(kind.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.unknown")); return 0; }
        var members = SettlementSpawner.spawn(source.getLevel(), pos, preset);
        if (members.isEmpty()) { source.sendFailure(Component.translatable("commands.mcyukkuri.settlement.blocked")); return 0; }
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.settlement.spawned", members.size()), true);
        return members.size();
    }
    private SettlementCommands() {}
}
