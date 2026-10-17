package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.settlement.SettlementData.SiteState;
import io.github.longshou.mcyukkuri.settlement.site.SiteBuilder;
import io.github.longshou.mcyukkuri.settlement.site.SiteGeom;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Slow life of a planned site (project-original numbers). Every pass is 200 loaded ticks, only for sites with a player within
 * 128 blocks and a fully loaded footprint, and touches at most three blocks: no offline catch-up, no scans of the world.
 * SEED -> FOUNDED -> THRIVING / STRAINED -> DECLINING -> ABANDONED; an abandoned site is open to the next settlers.
 */
final class SiteUpkeep {
    private static final long ABANDON_TICKS = 14L * 24000;
    private static final int PASS = 200, MEADOW_PER_RESIDENT = 24;

    static void tick(ServerLevel level) {
        var data = SettlementData.get(level);
        for (var site : data.sites()) {
            if (site.state == SiteState.SEED) continue;
            boolean watched = false;
            for (ServerPlayer player : level.players())
                if (!player.isSpectator() && player.distanceToSqr(site.origin.getX() + .5, site.origin.getY(), site.origin.getZ() + .5) <= 128.0 * 128.0) { watched = true; break; }
            var plan = site.plan();
            if (!watched || plan == null || !SiteBuilder.loaded(level, plan)) continue;
            var group = data.group(site.group);
            int bound = site.tier.bound;
            int population = group == null ? 0 : level.getEntitiesOfClass(YukkuriEntity.class, new AABB(site.origin).inflate(bound, 12, bound),
                    e -> e.isAlive() && group.id.equals(e.settlement().groupId())).size();
            int homes = site.slots(SiteSlot.Kind.HOME).size() + site.slots(SiteSlot.Kind.HALL).size();
            int capacity = Math.max(1, homes * 3);
            int stock = SiteLife.stock(level, site);
            var before = site.state;
            if (population == 0) {
                site.emptySince += PASS;
                site.state = site.emptySince >= ABANDON_TICKS ? SiteState.ABANDONED : SiteState.DECLINING;
                if (site.state == SiteState.ABANDONED) { site.group = null; site.prosperity = 0; }
            } else {
                site.emptySince = 0;
                boolean strained = population * 10 >= capacity * 9 || Seasons.at(level, site.origin) == Season.WINTER && stock == 0;
                site.state = population * 5 < capacity * 2 ? SiteState.DECLINING : strained ? SiteState.STRAINED : site.prosperity >= 1 ? SiteState.THRIVING : SiteState.FOUNDED;
                boolean healthy = !strained && stock >= population;
                if (level.random.nextInt(6) == 0) site.prosperity = Math.max(0, Math.min(3, site.prosperity + (healthy ? 1 : -1)));
                if (site.prosperity >= 1) wearPath(level, site, true);
                if (site.prosperity >= 1) regrow(level, site, population);
            }
            if (site.state == SiteState.ABANDONED) wearPath(level, site, false);
            if (site.state != before) data.setDirty();
        }
    }

    /** Trodden paths harden into dirt path while the site is lived in and soften back to coarse dirt when it is empty. */
    private static void wearPath(ServerLevel level, SettlementData.Site site, boolean walked) {
        var keys = new ArrayList<>(site.plan().paths.keySet());
        if (keys.isEmpty()) return;
        long key = keys.get(level.random.nextInt(keys.size()));
        int x = SiteGeom.keyX(key), z = SiteGeom.keyZ(key);
        var probe = new BlockPos(x, site.origin.getY() + 3, z);
        if (!level.hasChunkAt(probe)) return;
        for (int y = probe.getY(); y > probe.getY() - 9; y--) {
            var pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) continue;
            if (!level.getBlockState(pos.above()).isAir()) return;
            if (walked && state.is(Blocks.COARSE_DIRT)) level.setBlock(pos, Blocks.DIRT_PATH.defaultBlockState(), 2);
            else if (!walked && state.is(Blocks.DIRT_PATH)) level.setBlock(pos, Blocks.COARSE_DIRT.defaultBlockState(), 2);
            return;
        }
    }

    /** One tuft per pass on bare turf of the meadow ring, only while the ring holds fewer tufts than the residents can eat. */
    private static void regrow(ServerLevel level, SettlementData.Site site, int population) {
        var plan = site.plan();
        int dx = level.random.nextInt(site.tier.bound * 2 + 1) - site.tier.bound, dz = level.random.nextInt(site.tier.bound * 2 + 1) - site.tier.bound;
        int x = site.origin.getX() + dx, z = site.origin.getZ() + dz;
        if (Math.hypot(dx, dz) > plan.meadowOuter || plan.flat.contains(SiteGeom.key(x, z)) || plan.paths.containsKey(SiteGeom.key(x, z))
                || plan.hedge.containsKey(SiteGeom.key(x, z))) return;
        int tufts = 0;
        for (int i = 0; i < 24; i++) {
            var sample = new BlockPos(site.origin.getX() + level.random.nextInt(21) - 10, site.origin.getY(), site.origin.getZ() + level.random.nextInt(21) - 10);
            if (level.hasChunkAt(sample) && level.getBlockState(sample).is(Blocks.SHORT_GRASS)) tufts++;
        }
        if (tufts * 4 >= Math.min(24, population * MEADOW_PER_RESIDENT / 4)) return;
        var probe = new BlockPos(x, site.origin.getY() + 3, z);
        if (!level.hasChunkAt(probe)) return;
        for (int y = probe.getY(); y > probe.getY() - 9; y--) {
            var pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.is(Blocks.GRASS_BLOCK) && level.getBlockState(pos.above()).isAir()) level.setBlock(pos.above(), Blocks.SHORT_GRASS.defaultBlockState(), 2);
            return;
        }
    }
    private SiteUpkeep() {}
}
