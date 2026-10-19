package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.settlement.site.SiteBuilder;
import io.github.longshou.mcyukkuri.settlement.site.WildSettlementPiece;
import io.github.longshou.mcyukkuri.settlement.site.WildSettlementStructure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Finds generated settlement grounds near players and moves a community in, one site per level per pass. Nothing here
 * loads or generates chunks: starts are read from chunks that are already loaded, and a site is claimed only when every
 * chunk of its footprint is, so no population is created for ground nobody can see.
 */
public final class SiteClaimer {
    /** Chunks around each player that are scanned for structure starts. */
    private static final int SCAN_CHUNKS = 8;
    private static final int PASS_TICKS = 100;
    /** A failed claim waits this long (game ticks) before the next try; five failures wait a whole day. */
    private static final int RETRY_TICKS = 6000;

    /** Register once on the game event bus. */
    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % PASS_TICKS != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) continue;
            discover(level);
            if (YukkuriConfig.NATURAL_SPAWNS.get() && YukkuriConfig.COMMUNITIES.get()) claim(level);
            if (server.getTickCount() % (PASS_TICKS * 2) == 0) SiteUpkeep.tick(level);
        }
    }

    /** Registers structure starts of loaded chunks around the players as empty sites. */
    private static void discover(ServerLevel level) {
        var data = SettlementData.get(level);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            int px = player.blockPosition().getX() >> 4, pz = player.blockPosition().getZ() >> 4;
            for (int cx = px - SCAN_CHUNKS; cx <= px + SCAN_CHUNKS; cx++) for (int cz = pz - SCAN_CHUNKS; cz <= pz + SCAN_CHUNKS; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var entry : chunk.getAllStarts().entrySet()) {
                    if (!(entry.getKey() instanceof WildSettlementStructure) || !entry.getValue().isValid()) continue;
                    for (var piece : entry.getValue().getPieces()) {
                        if (!(piece instanceof WildSettlementPiece generated)) continue;
                        var plan = generated.plan();
                        if (plan != null) data.addSite(plan, true);
                    }
                }
            }
        }
    }

    private static void claim(ServerLevel level) {
        var data = SettlementData.get(level);
        double reach = 96.0 * 96.0;
        for (var site : data.sites()) {
            if (!site.auto || site.state != SettlementData.SiteState.SEED || !site.vacant() || level.getGameTime() < site.retryAt) continue;
            var plan = site.plan();
            if (plan == null) continue;
            boolean near = false;
            for (ServerPlayer player : level.players())
                if (!player.isSpectator() && player.distanceToSqr(site.origin.getX() + .5, site.origin.getY(), site.origin.getZ() + .5) <= reach) { near = true; break; }
            if (!near || !SiteBuilder.loaded(level, plan)) continue;
            SiteLife.clearLanes(level, site);
            boolean leader = level.random.nextInt(100) < YukkuriConfig.LEADER_CHANCE.get();
            var members = SettlementSpawner.spawnAtSite(level, site, SettlementComposition.forTier(site.tier, level.random, leader));
            if (members.isEmpty()) {
                site.failures++;
                site.retryAt = level.getGameTime() + (site.failures >= 5 ? RETRY_TICKS * 4L : RETRY_TICKS);
            }
            return;
        }
    }
    private SiteClaimer() {}
}
