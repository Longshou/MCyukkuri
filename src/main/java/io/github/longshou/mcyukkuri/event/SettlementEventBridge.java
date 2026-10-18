package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.settlement.SettlementEvents;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import net.minecraft.server.level.ServerLevel;

/** Calls the existing authorities at the caller's original decision point. */
public final class SettlementEventBridge implements SettlementEvents.Port {
    @Override public boolean reserved(YukkuriEntity entity) { return WildEvents.reserved(entity); }
    @Override public boolean homeClaimHeld(YukkuriEntity entity) { return HomeClaim.holds(entity); }
    // Keep the migration eligibility projection: no owner and no pending staff assignment.
    @Override public boolean unowned(YukkuriEntity entity) { return Custody.view(entity).unassigned(); }
    @Override public boolean shelterBarred(YukkuriEntity entity) { return GroupCards.barred(entity); }
    @Override public boolean tryCollapse(ServerLevel level, ShelterBlockEntity shelter) { return GroupCards.collapse(level, shelter); }
}
