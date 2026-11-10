package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.settlement.site.SiteBuilder;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import io.github.longshou.mcyukkuri.world.SearchBudget.Cost;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Each ground is surveyed once, from the centre with the weakest walking body. */
final class MigrationSearch {
    static final int SEARCH_TICKS = 1200;
    private static final int COLUMNS = 24, THREAT_GAP = 24;
    private static final Cost SITE_SLICE = new Cost(2048, 0, 1);

    private final WeakReference<YukkuriEntity> owner;
    private final WeakReference<ServerLevel> world;
    private final BlockPos from;
    private final long generation = SearchBudget.generation();
    private final long deadline;
    final String reason;
    final boolean forced;
    private final List<BlockPos> sites;
    private final boolean nearby;
    private final Set<BlockPos> tried;
    private final UUID weakest;
    private final int households;
    private final int existingHomes;
    private int siteIndex, columns;
    private BlockPos candidate;
    private long lastSlice = Long.MIN_VALUE;
    private String failure = "no_dry_site";

    MigrationSearch(ServerLevel level, YukkuriEntity leader, SettlementData.Group group, boolean forced, String reason,
                    Set<BlockPos> excluded, List<BlockPos> nearbySites) {
        owner = new WeakReference<>(leader);
        world = new WeakReference<>(level);
        from = group.center;
        deadline = level.getGameTime() + SEARCH_TICKS;
        this.forced = forced;
        this.reason = reason;
        tried = new HashSet<>(excluded);
        nearby = nearbySites != null;
        sites = nearby ? nearbySites : plannedSites(level, group);
        var members = Migration.members(level, group);
        weakest = members.stream().filter(member -> Migration.walks(member, group))
                .min(Comparator.<YukkuriEntity>comparingDouble(member -> BodyAbility.of(member).stepUp())
                        .thenComparingDouble(member -> BodyAbility.of(member).walkSpeed()))
                .orElse(leader).getUUID();
        households = Homes.migrationHouseholds(members, group);
        var homes = new HashSet<BlockPos>();
        for (var member : members) if (Migration.joins(member, group) && member.life().homeStand() != null
                && member.life().homeStand().distSqr(from) <= 32 * 32) {
            var shelter = member.habitat().shelter();
            homes.add(shelter == null ? member.life().homeStand() : shelter.getBlockPos());
        }
        existingHomes = homes.size();
    }

    private static List<BlockPos> plannedSites(ServerLevel level, SettlementData.Group group) {
        var result = new ArrayList<BlockPos>();
        for (var site : SettlementData.get(level).sites()) {
            if (!site.vacant() || site.id.equals(group.siteId)) continue;
            var plaza = SiteLife.anchor(site, SiteSlot.Kind.PLAZA);
            if (plaza != null && plaza.distSqr(group.center) >= 24 * 24 && plaza.distSqr(group.center) <= 80 * 80)
                result.add(plaza);
        }
        result.sort(Comparator.comparingDouble(group.center::distSqr));
        return result;
    }

    boolean ownedBy(YukkuriEntity entity) { return owner.get() == entity; }
    boolean current(ServerLevel level, YukkuriEntity leader, SettlementData.Group group) {
        return world.get() == level && ownedBy(leader) && from.equals(group.center);
    }
    void cancel() {
        var level = world.get();
        var entity = owner.get();
        if (level != null && entity != null) SearchBudget.cancel(level, entity, Purpose.PICK_SITE, generation);
    }
    BlockPos result() { return candidate; }
    String failure() { return failure; }
    String progress() {
        return "planned=" + siteIndex + "/" + sites.size() + " columns=" + columns + " households=" + households;
    }

    Result advance(ServerLevel level, YukkuriEntity owner, SettlementData.Group group, long horizon) {
        long now = level.getGameTime();
        var walker = level.getEntity(weakest) instanceof YukkuriEntity member && member.isAlive() ? member : owner;
        if (now >= Math.min(deadline, horizon) || siteIndex >= sites.size() && (nearby || columns >= COLUMNS))
            return localGround(level, walker, group);
        if (lastSlice == now) return Result.DEFERRED;
        var lease = SearchBudget.request(level, owner, Purpose.PICK_SITE, generation, SITE_SLICE);
        if (lease == null) return Result.DEFERRED;
        lastSlice = now;
        try (lease; var scope = lease.bind(walker)) {
            var next = siteIndex < sites.size() ? sites.get(siteIndex++) : naturalSite(level, owner, group);
            if (next == null || !tried.add(next)) return Result.DEFERRED;
            var targets = Migration.approaches(walker, next, false);
            if (targets.isEmpty()) return Result.DEFERRED;
            if (!nearby && group.threatPos != null && next.distSqr(group.threatPos) < THREAT_GAP * THREAT_GAP) return Result.DEFERRED;
            var site = SiteLife.bySpot(SettlementData.get(level), next);
            if (site != null && (!site.vacant() || !SiteBuilder.loaded(level, site.plan()))) return Result.DEFERRED;
            if (!Homes.nestable(level, next, site, households, walker)) {
                failure = "no_family_homes";
                return Result.DEFERRED;
            }
            SearchBudget.path(walker);
            var start = centreStand(walker);
            if (start == null || !walker.life().dryRouteFrom(start, targets)) {
                failure = "no_dry_route";
                return Result.DEFERRED;
            }
            candidate = next;
            return Result.FOUND;
        }
    }

    private BlockPos centreStand(YukkuriEntity walker) {
        return Migration.approaches(walker, from, false).stream().min(Comparator.comparingDouble(from::distSqr)).orElse(null);
    }

    private Result localGround(ServerLevel level, YukkuriEntity walker, SettlementData.Group group) {
        if (centreStand(walker) != null && Homes.nestable(level, from, SettlementData.get(level).site(group.siteId),
                households - existingHomes, walker)) {
            candidate = from;
            return Result.FOUND;
        }
        failure = "no_nestable_ground";
        return Result.NOT_FOUND;
    }

    private BlockPos naturalSite(ServerLevel level, YukkuriEntity owner, SettlementData.Group group) {
        columns++;
        var random = owner.getRandom();
        double angle = random.nextDouble() * 2 * Math.PI;
        var danger = group.threatPos;
        if (danger != null && group.threat > 0) {
            double away = Math.atan2(from.getZ() - danger.getZ(), from.getX() - danger.getX());
            angle = away + (random.nextDouble() - .5) * Math.PI * 4 / 3;
        }
        int min = YukkuriConfig.MIGRATE_MIN_DISTANCE.get();
        int max = Math.max(min, YukkuriConfig.MIGRATE_MAX_DISTANCE.get() * 2);
        double distance = min + random.nextDouble() * (max - min);
        var column = from.offset((int)Math.round(Math.cos(angle) * distance), 0, (int)Math.round(Math.sin(angle) * distance));
        return level.hasChunkAt(column) ? level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column) : null;
    }
}
