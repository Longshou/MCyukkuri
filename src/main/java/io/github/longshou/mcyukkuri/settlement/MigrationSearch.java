package io.github.longshou.mcyukkuri.settlement;

import static io.github.longshou.mcyukkuri.world.SearchBudget.generation;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.settlement.site.SiteBuilder;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import io.github.longshou.mcyukkuri.world.SearchBudget.Cost;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Server-thread, transient cursor; a slice never carries its lease into the next tick. */
final class MigrationSearch {
    private static final int SLICE = 4096, TRIES = 24, THREAT_GAP = 24;
    private static final int ROOM_COST = 1 + 17 * 5 * 17 * 2, GRASS_COST = 21 * 7 * 21;
    private enum Stage { SITES, COLUMNS, SCORE, FINISH }
    private record Candidate(BlockPos pos, int grass) {}

    private final WeakReference<YukkuriEntity> owner;
    private final WeakReference<ServerLevel> world;
    private final BlockPos from, danger;
    private final UUID oldSite;
    private final boolean fleeing, hungry, afloat;
    private final int min, max;
    private final long generation = generation();
    final String reason;
    final boolean forced;
    private final List<UUID> sites;
    private final List<Candidate> candidates = new ArrayList<>();
    private Stage stage = Stage.SITES;
    private int siteIndex, tries, dy;
    private UUID bestSite;
    private double nearest = Double.MAX_VALUE, base;
    private boolean bearingChosen;
    private BlockPos column, scoring, result;
    private long lastSlice = Long.MIN_VALUE;

    MigrationSearch(ServerLevel level, YukkuriEntity leader, SettlementData.Group group, boolean forced, String reason) {
        owner = new WeakReference<>(leader); world = new WeakReference<>(level);
        from = group.center.immutable(); danger = group.threatPos == null ? null : group.threatPos.immutable();
        oldSite = group.siteId; fleeing = danger != null && group.threat > 0;
        afloat = Migration.afloat(group);
        min = YukkuriConfig.MIGRATE_MIN_DISTANCE.get(); max = Math.max(min, YukkuriConfig.MIGRATE_MAX_DISTANCE.get() * 2);
        this.forced = forced; this.reason = reason;
        hungry = "famine".equals(reason) || "worn_out".equals(reason);
        sites = SettlementData.get(level).sites().stream().map(site -> site.id).toList();
    }

    boolean ownedBy(YukkuriEntity entity) { return owner.get() == entity; }
    boolean current(ServerLevel level, YukkuriEntity leader, SettlementData.Group group) {
        return world.get() == level && ownedBy(leader) && from.equals(group.center)
                && Objects.equals(danger, group.threatPos) && Objects.equals(oldSite, group.siteId)
                && fleeing == (group.threatPos != null && group.threat > 0)
                && afloat == Migration.afloat(group)
                && min == YukkuriConfig.MIGRATE_MIN_DISTANCE.get()
                && max == Math.max(min, YukkuriConfig.MIGRATE_MAX_DISTANCE.get() * 2);
    }
    void cancel() {
        var level = world.get(); var entity = owner.get();
        if (level != null && entity != null) SearchBudget.cancel(level, entity, Purpose.PICK_SITE, generation);
    }
    BlockPos result() { return result; }

    Result advance(ServerLevel level, YukkuriEntity leader) {
        if (lastSlice == level.getGameTime()) return Result.DEFERRED;
        var lease = SearchBudget.request(level, leader, Purpose.PICK_SITE, generation, new Cost(SLICE, 0, 0));
        if (lease == null) return Result.DEFERRED;
        lastSlice = level.getGameTime();
        try (lease; var scope = lease.bind(leader)) {
            int left = SLICE;
            while (left > 0) {
                switch (stage) {
                    case SITES -> {
                        if (siteIndex < sites.size()) {
                            UUID id = sites.get(siteIndex++);
                            var plaza = vacantSite(level, id); left--;
                            if (plaza != null && plaza.distSqr(from) < nearest) {
                                nearest = plaza.distSqr(from); bestSite = id;
                            }
                        } else {
                            // Occupancy and loaded chunks may have changed while this cursor was waiting.
                            if (bestSite != null) {
                                result = vacantSite(level, bestSite); left--;
                                if (result != null) return Result.FOUND;
                            }
                            stage = Stage.COLUMNS;
                        }
                    }
                    case COLUMNS -> {
                        if (candidates.size() >= 3 || column == null && tries >= TRIES) {
                            stage = Stage.FINISH; continue;
                        }
                        if (column == null) {
                            SearchBudget.probe(level); left--; tries++;
                            if (!bearingChosen) {
                                base = leader.getRandom().nextDouble() * Math.PI * 2;
                                if (fleeing && (danger.getX() != from.getX() || danger.getZ() != from.getZ()))
                                    base = Math.atan2(from.getZ() - danger.getZ(), from.getX() - danger.getX());
                                bearingChosen = true;
                            }
                            double spread = fleeing ? Math.PI * 2 / 3 : Math.PI * 2;
                            double angle = base + (leader.getRandom().nextDouble() - .5) * spread;
                            double distance = min + leader.getRandom().nextDouble() * (max - min);
                            var next = from.offset((int)Math.round(Math.cos(angle) * distance), 0, (int)Math.round(Math.sin(angle) * distance));
                            if (!level.hasChunkAt(next) || nearDanger(next)) continue;
                            column = next; dy = 10;
                        }
                        if (left < ROOM_COST) return Result.DEFERRED;
                        var spot = column.offset(0, dy--, 0);
                        boolean suitable = suitable(level, leader, spot); left -= ROOM_COST;
                        if (suitable) {
                            scoring = spot.immutable(); column = null; stage = Stage.SCORE;
                        } else if (dy < -12) column = null;
                    }
                    case SCORE -> {
                        if (hungry && left < GRASS_COST) return Result.DEFERRED;
                        int grass = hungry ? grazing(level, scoring) : 0;
                        if (hungry) left -= GRASS_COST;
                        candidates.add(new Candidate(scoring, grass)); scoring = null; stage = Stage.COLUMNS;
                    }
                    case FINISH -> {
                        if (candidates.isEmpty()) return Result.NOT_FOUND;
                        if (left < ROOM_COST) return Result.DEFERRED;
                        Candidate best = candidates.getFirst();
                        for (var candidate : candidates) if (candidate.grass > best.grass) best = candidate;
                        candidates.remove(best);
                        boolean valid = suitable(level, leader, best.pos); left -= ROOM_COST;
                        if (valid) { result = best.pos; return Result.FOUND; }
                    }
                }
            }
            return Result.DEFERRED;
        }
    }

    private boolean nearDanger(BlockPos pos) { return danger != null && pos.distSqr(danger) < THREAT_GAP * THREAT_GAP; }
    private BlockPos vacantSite(ServerLevel level, UUID id) {
        SearchBudget.probe(level);
        var site = SettlementData.get(level).site(id);
        if (site == null || !site.vacant() || site.id.equals(oldSite)) return null;
        var plaza = SiteLife.anchor(site, SiteSlot.Kind.PLAZA); var plan = site.plan();
        if (plaza == null || plan == null || plaza.distSqr(from) < 24 * 24 || plaza.distSqr(from) > 80 * 80
                || nearDanger(plaza) || !SiteBuilder.loaded(level, plan) || afloat && !Migration.waterBankReady(level, plaza, owner.get())) return null;
        return plaza;
    }
    private boolean suitable(ServerLevel level, YukkuriEntity leader, BlockPos spot) {
        SearchBudget.probe(level);
        if (!level.hasChunkAt(spot) || nearDanger(spot) || !leader.life().dryStand(spot)) return false;
        if (afloat) return Migration.waterBankReady(level, spot, leader);
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-8, -2, -8), spot.offset(8, 2, 8)))
            if (NaturalShelter.enclosed(level, pos) && ++found >= 3) return true;
        return false;
    }
    private static int grazing(ServerLevel level, BlockPos spot) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-10, -3, -10), spot.offset(10, 3, 10))) {
            SearchBudget.probe(level);
            if (level.hasChunkAt(pos) && Grazing.edible(level.getBlockState(pos))) count++;
        }
        return count;
    }
}
