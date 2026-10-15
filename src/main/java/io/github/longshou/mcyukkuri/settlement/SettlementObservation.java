package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.network.chat.Component;

public final class SettlementObservation {
    public static Component page(YukkuriEntity entity) {
        var life = entity.settlement(); var group = life.group();
        return Component.translatable("notebook.mcyukkuri.settlement",
                Component.translatable("settlement.mcyukkuri." + life.kind()),
                life.groupId() == null ? "—" : life.groupId().toString().substring(0, 8),
                group == null ? "—" : group.center.toShortString(),
                group == null || group.leader == null ? Component.translatable("settlement.mcyukkuri.no_leader")
                        : Component.translatable("settlement.mcyukkuri.patchouli", group.leader.toString().substring(0, 8)),
                Component.translatable(Seasons.enabled() ? "season.mcyukkuri." + Seasons.of(entity).id() : "season.mcyukkuri.off"));
    }
    private SettlementObservation() {}
}
