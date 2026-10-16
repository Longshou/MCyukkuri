package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.server.level.ServerLevel;

/**
 * Compatibility name for callers compiled against the former automatic election.
 * New code invokes {@link LeaderSuccession}; this facade cannot select a leader without its witnesses.
 */
@Deprecated
final class Succession {
    static void tick(YukkuriEntity caller, SettlementData.Group group) {
        if (caller.level() instanceof ServerLevel level) LeaderSuccession.tick(level, SettlementData.get(level), group);
    }

    private Succession() {}
}
