package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The common store (公仓) of a led community: the granary piles of its planned site, or, for a community living in
 * natural caves, a remembered real pile initially entrusted to the leader's household.
 * Only real piles in loaded chunks count; nothing here creates food.
 */
public final class CommonStore {
    public static List<ForagePileBlockEntity> piles(ServerLevel level, SettlementData.Group group) {
        var out = new ArrayList<ForagePileBlockEntity>();
        if (group == null) return out;
        var site = SiteLife.of(level, group);
        if (site != null) {
            out.addAll(StorePiles.atSite(level, site));
            if (!out.isEmpty()) return out;
        }
        if (group.landCommons != null && level.hasChunkAt(group.landCommons)
                && level.getBlockEntity(group.landCommons) instanceof ForagePileBlockEntity pile && !pile.isRemoved()) out.add(pile);
        return out;
    }

    /** Called by the group clock, never by stock queries. A change of leader does not move the food. */
    static void remember(ServerLevel level, SettlementData.Group group) {
        if (!CommunityRules.ruled(group) || group.siteId != null) return;
        if (group.landCommons != null) {
            if (!level.hasChunkAt(group.landCommons)) return;
            if (level.getBlockEntity(group.landCommons) instanceof ForagePileBlockEntity pile && !pile.isRemoved()) return;
            group.commons.note(level.getGameTime(), "store_lost", null, group.landCommons.toShortString());
            group.landCommons = null;
            SettlementData.get(level).setDirty();
        }
        var home = leaderHome(level, group);
        if (home == null || home.getBlockPos().distSqr(group.center) > 48 * 48) return;
        var pile = home.pile();
        if (pile == null || pile.isRemoved()) return;
        group.landCommons = pile.getBlockPos().immutable();
        group.commons.note(level.getGameTime(), "store_kept", group.leader, group.landCommons.toShortString());
        SettlementData.get(level).setDirty();
    }

    /** Read the actual burrow while the keeper is present, including the final death notification. */
    static ShelterBlockEntity leaderHome(ServerLevel level, SettlementData.Group group) {
        if (group.leader == null || !(level.getEntity(group.leader) instanceof YukkuriEntity leader)) return null;
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
        return StorePiles.nearest(piles(level, group), from, ok);
    }

    /** True when {@code pile} belongs to the commons of {@code group}. */
    public static boolean isCommon(ServerLevel level, SettlementData.Group group, ForagePileBlockEntity pile) {
        if (pile == null) return false;
        for (var p : piles(level, group)) if (p.getBlockPos().equals(pile.getBlockPos())) return true;
        return false;
    }

    private CommonStore() {}
}
