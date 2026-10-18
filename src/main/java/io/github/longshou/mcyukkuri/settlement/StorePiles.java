package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Ordered reads of a site's real STORE slots. Enumeration never creates or links a pile. */
public final class StorePiles {
    public static List<ForagePileBlockEntity> atSite(ServerLevel level, SettlementData.Site site) {
        return atSite(level, site, false);
    }
    /** Preserves the historical SiteLife.stock read, which did not exclude removed block entities. */
    public static List<ForagePileBlockEntity> atSiteIncludingRemoved(ServerLevel level, SettlementData.Site site) {
        return atSite(level, site, true);
    }
    private static List<ForagePileBlockEntity> atSite(ServerLevel level, SettlementData.Site site, boolean includeRemoved) {
        var out = new ArrayList<ForagePileBlockEntity>();
        if (site == null) return List.of();
        for (var slot : site.slots(SiteSlot.Kind.STORE)) {
            var at = slot.anchor();
            if (level.hasChunkAt(at) && level.getBlockEntity(at) instanceof ForagePileBlockEntity pile
                    && (includeRemoved || !pile.isRemoved())) out.add(pile);
        }
        // Slot order and duplicates are significant; neither sorting nor set conversion belongs here.
        return List.copyOf(out);
    }
    /** Strictly nearer than 64 blocks; ties keep the first supplied pile. Predicate runs before distance. */
    public static ForagePileBlockEntity nearest(List<ForagePileBlockEntity> piles, BlockPos from, Predicate<ForagePileBlockEntity> ok) {
        ForagePileBlockEntity best = null;
        double distance = 64 * 64;
        for (var pile : piles) {
            if (!ok.test(pile)) continue;
            double d = pile.getBlockPos().distSqr(from);
            if (d < distance) { distance = d; best = pile; }
        }
        return best;
    }
    private StorePiles() {}
}
