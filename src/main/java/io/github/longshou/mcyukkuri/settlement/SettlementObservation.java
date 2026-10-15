package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import net.minecraft.network.chat.Component;

public final class SettlementObservation {
    public static Component page(YukkuriEntity entity) {
        var life = entity.settlement(); var group = life.group();
        return Component.translatable("notebook.mcyukkuri.settlement",
                Component.translatable("settlement.mcyukkuri." + life.kind()),
                life.groupId() == null ? "—" : life.groupId().toString().substring(0, 8),
                group == null ? "—" : group.center.toShortString(),
                group != null && group.vacant() ? Component.translatable("settlement.mcyukkuri.vacant")
                        : group == null || group.leader == null ? Component.translatable("settlement.mcyukkuri.no_leader")
                        : Component.translatable("settlement.mcyukkuri.leader", group.leader.toString().substring(0, 8)),
                Component.translatable(Seasons.enabled() ? "season.mcyukkuri." + Seasons.of(entity).id() : "season.mcyukkuri.off"),
                Component.translatable("settlement.mcyukkuri.role." + life.role().name().toLowerCase(java.util.Locale.ROOT)),
                Component.translatable("settlement.mcyukkuri.order." + life.orders().kind().name().toLowerCase(java.util.Locale.ROOT)),
                group == null ? "—" : Component.translatable("settlement.mcyukkuri.threat." + (group.threat <= 0 ? "calm"
                        : group.threat < Math.max(1, YukkuriConfig.MIGRATE_THRESHOLD.get()) ? "uneasy" : "alarmed"), group.threat),
                group == null || group.migrateTo == null ? Component.translatable("settlement.mcyukkuri.settled")
                        : Component.translatable("settlement.mcyukkuri.migrating", group.migrateTo.toShortString(),
                                Math.max(0, entity.level().getGameTime() - group.migrateSince) / 20));
    }
    private SettlementObservation() {}
}
