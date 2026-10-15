package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.network.chat.Component;

public final class SettlementObservation {
    public static Component page(YukkuriEntity entity) {
        var life = entity.settlement(); var group = life.group();
        var record = life.society().record();
        long now = entity.level().getGameTime();
        Component role = Component.translatable("settlement.mcyukkuri.role." + (group == null && life.groupId() != null ? "away"
                : life.society().role().name().toLowerCase(java.util.Locale.ROOT)));
        Component leader = group == null ? Component.translatable("settlement.mcyukkuri.no_leader")
                : group.leader != null ? Component.translatable("settlement.mcyukkuri.leader", group.leader.toString().substring(0, 8))
                : !group.hasLeaderOffice ? Component.translatable("settlement.mcyukkuri.no_office")
                : group.vacancyConfirmed ? Component.translatable("settlement.mcyukkuri.vacant", group.successionTicks / 20, LeaderSuccession.DELAY_TICKS / 20)
                : Component.translatable("settlement.mcyukkuri.unresolved_office");
        if (group != null && group.leader != null && io.github.longshou.mcyukkuri.settlement.SettlementData.get((net.minecraft.server.level.ServerLevel)entity.level()).activeLeader(group) == null)
            leader = Component.translatable("settlement.mcyukkuri.suspended", leader);
        return Component.translatable("notebook.mcyukkuri.settlement",
                Component.translatable("settlement.mcyukkuri." + life.kind()),
                life.groupId() == null ? "—" : life.groupId().toString().substring(0, 8),
                group == null ? "—" : group.center.toShortString(),
                leader, role, record == null ? 0 : record.rejections,
                record == null || record.observer == null ? "—" : record.observer.toString().substring(0, 8),
                record == null || record.assignedBy == null || record.assignedUntil <= now ? "—" : record.assignedBy.toString().substring(0, 8),
                Component.translatable("settlement.mcyukkuri." + (record != null && record.rememberedUntil > now ? "misgivings" : "no_misgivings")));
    }
    private SettlementObservation() {}
}
