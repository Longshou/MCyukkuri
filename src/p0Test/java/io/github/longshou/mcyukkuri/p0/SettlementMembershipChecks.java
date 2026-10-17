package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Remote regression for real entity membership transfers and their saved group ledger. */
final class SettlementMembershipChecks {
    static void run(ServerLevel level, BlockPos at, BiConsumer<String, Boolean> check) {
        var resident = ModEntities.REIMU.get().create(level);
        var data = SettlementData.get(level);
        var former = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FOREST, at, resident.getUUID());
        var next = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FAMILY, at.east(8), null);
        data.add(former);
        data.add(next);
        try {
            resident.settlement().join(former);
            data.setRole(former, resident.getUUID(), SettlementData.Role.SHUNNED);
            resident.settlement().join(former);
            check.accept("joining the same group preserves its witnessed standing",
                    data.existingMember(former, resident.getUUID()).role == SettlementData.Role.SHUNNED);
            data.restore(former, resident.getUUID(), level.getGameTime());
            resident.settlement().join(next);
            check.accept("moving to another group removes the previous membership and office",
                    data.existingMember(former, resident.getUUID()) == null && former.leader == null && former.vacant()
                    && former.vacantSince == Math.max(1, level.getGameTime())
                    && resident.settlement().group() == next
                    && data.existingMember(next, resident.getUUID()).role == SettlementData.Role.MEMBER);
            var saved = SettlementData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            check.accept("membership transfer persists without resurrecting the former leader",
                    saved.existingMember(saved.group(former.id), resident.getUUID()) == null
                    && saved.group(former.id).vacant()
                    && saved.existingMember(saved.group(next.id), resident.getUUID()).role == SettlementData.Role.MEMBER);
            var memberTag = resident.settlement().save();
            resident.settlement().load(memberTag);
            check.accept("entity reload retains the new group and order inventory container",
                    resident.settlement().group() == next && memberTag.contains("Orders", net.minecraft.nbt.Tag.TAG_COMPOUND));
        } finally {
            resident.discard();
            data.remove(former.id);
            data.remove(next.id);
        }
    }

    private SettlementMembershipChecks() {}
}
