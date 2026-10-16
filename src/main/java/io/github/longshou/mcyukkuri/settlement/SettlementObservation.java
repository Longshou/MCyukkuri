package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import net.minecraft.network.chat.Component;

public final class SettlementObservation {
    /** Social standing remains on its existing compact page. */
    public static Component page(YukkuriEntity entity) {
        var life = entity.settlement();
        var group = life.group();
        var record = life.society().record();
        long now = entity.level().getGameTime();
        Component role = Component.translatable("settlement.mcyukkuri.role." + (group == null && life.groupId() != null ? "away"
                : life.society().role().name().toLowerCase(java.util.Locale.ROOT)));
        Component leader = group == null ? Component.translatable("settlement.mcyukkuri.no_leader")
                : group.leader != null ? Component.translatable("settlement.mcyukkuri.leader", group.leader.toString().substring(0, 8))
                : !group.hasLeaderOffice ? Component.translatable("settlement.mcyukkuri.no_office")
                : group.vacancyConfirmed ? Component.translatable("settlement.mcyukkuri.vacant", group.successionTicks / 20, LeaderSuccession.DELAY_TICKS / 20)
                : Component.translatable("settlement.mcyukkuri.unresolved_office");
        if (group != null && group.leader != null && !entity.level().isClientSide
                && SettlementData.get((net.minecraft.server.level.ServerLevel)entity.level()).activeLeader(group) == null) {
            leader = Component.translatable("settlement.mcyukkuri.suspended", leader);
        }
        return Component.translatable("notebook.mcyukkuri.settlement",
                Component.translatable("settlement.mcyukkuri." + life.kind()),
                life.groupId() == null ? "—" : life.groupId().toString().substring(0, 8),
                group == null ? "—" : group.center.toShortString(),
                leader, role, record == null ? 0 : record.rejections,
                record == null || record.observer == null ? "—" : record.observer.toString().substring(0, 8),
                record == null || record.assignedBy == null || record.assignedUntil <= now ? "—" : record.assignedBy.toString().substring(0, 8),
                Component.translatable("settlement.mcyukkuri." + (record != null && record.rememberedUntil > now ? "misgivings" : "no_misgivings")));
    }

    /** Seasonal gathering, dispatch, danger reports and migration form a separate four-line page. */
    public static Component ordersPage(YukkuriEntity entity) {
        var life = entity.settlement();
        var group = life.group();
        Component season = Component.translatable(Seasons.enabled() ? "season.mcyukkuri." + Seasons.of(entity).id() : "season.mcyukkuri.off");
        Component order = Component.translatable("settlement.mcyukkuri.order." + life.orders().kind().name().toLowerCase(java.util.Locale.ROOT));
        Component threat = group == null ? Component.translatable("settlement.mcyukkuri.threat.calm")
                : Component.translatable("settlement.mcyukkuri.threat." + (group.threat <= 0 ? "calm"
                        : group.threat < Math.max(1, YukkuriConfig.MIGRATE_THRESHOLD.get()) ? "uneasy" : "alarmed"), group.threat);
        Component migration = group == null || group.migrateTo == null ? Component.translatable("settlement.mcyukkuri.settled")
                : Component.translatable("settlement.mcyukkuri.migrating", group.migrateTo.toShortString(),
                        Math.max(0, entity.level().getGameTime() - group.migrateSince) / 20);
        return Component.translatable("notebook.mcyukkuri.settlement_orders", season, order, threat, migration);
    }

    private SettlementObservation() {}
}
