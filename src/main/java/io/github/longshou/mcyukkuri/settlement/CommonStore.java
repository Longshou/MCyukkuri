package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The common store (公仓) of a led community: the granary piles of its planned site, or, for a community living in
 * natural caves, the store pile of the leader's own burrow (project design: the leader keeps what the hunt brings).
 * Only real piles in loaded chunks count; nothing here creates food.
 */
public final class CommonStore {
    public static List<ForagePileBlockEntity> piles(ServerLevel level, SettlementData.Group group) {
        var out = new ArrayList<ForagePileBlockEntity>();
        if (group == null) return out;
        var site = SiteLife.of(level, group);
        if (site != null) {
            for (var slot : site.slots(SiteSlot.Kind.STORE)) {
                var at = slot.anchor();
                if (level.hasChunkAt(at) && level.getBlockEntity(at) instanceof ForagePileBlockEntity pile && !pile.isRemoved()) out.add(pile);
            }
            if (!out.isEmpty()) return out;
        }
        var leader = leaderHome(level, group);
        if (leader != null) {
            var pile = leader.pile();
            if (pile != null && !pile.isRemoved()) out.add(pile);
        }
        return out;
    }

    /** The leader's burrow, only while the leader is loaded (its own store is the commons of a cave community). */
    static ShelterBlockEntity leaderHome(ServerLevel level, SettlementData.Group group) {
        if (group.leader == null || !(level.getEntity(group.leader) instanceof YukkuriEntity leader) || !leader.isAlive()) return null;
        return leader.habitat().shelter();
    }

    public static int stock(ServerLevel level, SettlementData.Group group) {
        int total = 0;
        for (var pile : piles(level, group)) total += pile.food();
        return total;
    }

    public static int capacity(ServerLevel level, SettlementData.Group group) {
        int total = 0;
        for (var pile : piles(level, group)) total += pile.capacity();
        return total;
    }

    /** The nearest common pile (within 64 blocks) that satisfies {@code ok}. */
    public static ForagePileBlockEntity nearest(ServerLevel level, SettlementData.Group group, BlockPos from, Predicate<ForagePileBlockEntity> ok) {
        ForagePileBlockEntity best = null;
        double distance = 64 * 64;
        for (var pile : piles(level, group)) {
            if (!ok.test(pile)) continue;
            double d = pile.getBlockPos().distSqr(from);
            if (d < distance) { distance = d; best = pile; }
        }
        return best;
    }

    /** True when {@code pile} belongs to the commons of {@code group}. */
    public static boolean isCommon(ServerLevel level, SettlementData.Group group, ForagePileBlockEntity pile) {
        if (pile == null) return false;
        for (var p : piles(level, group)) if (p.getBlockPos().equals(pile.getBlockPos())) return true;
        return false;
    }

    private CommonStore() {}
}
