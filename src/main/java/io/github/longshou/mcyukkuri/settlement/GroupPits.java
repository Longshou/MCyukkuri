package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The shared waste pit of a community (project design; wild yukkuri have no toilets, only a bare hollow a little away from
 * the homes). A planned site uses the pit its ground was laid out with; a forest community on natural caves picks a bare,
 * open hollow once and keeps it in its group record until it moves. Families and lone wild yukkuri have no pit: they use
 * suitable ground away from their home. Nothing here creates waste or moves anybody: it only answers where to walk.
 */
public final class GroupPits {
    /** A member goes to the pit only from this close (blocks); the pit of a natural community lies within this of its centre. */
    public static final int REACH = 32;
    /** Beyond this the pathfinder's range ends: the walk goes by partial paths and reachability is checked once nearer. */
    public static final int NEAR = 14;
    /** After a refused or failed trip the member uses plain ground for this long. */
    private static final int REFUSE_TICKS = 1200;
    private static final Map<YukkuriEntity, Long> REFUSED = new WeakHashMap<>();
    /** A natural community that found no pit looks again only after this long (transient, one entry per loaded group). */
    private static final int SEARCH_RETRY_TICKS = 6000;
    private static final Map<SettlementData.Group, Long> SEARCHED = new WeakHashMap<>();

    /** The standing cells of the group's pit, or empty when the group has none. */
    public static List<BlockPos> cells(ServerLevel level, SettlementData.Group group) {
        if (group == null || group.waterCommons != null) return List.of();
        var site = SiteLife.of(level, group);
        if (site != null) return SiteLife.pit(site);
        if (group.kind != SettlementData.Kind.FOREST) return List.of();
        if (group.pit == null || group.pit.distSqr(group.center) > REACH * REACH) {
            var last = SEARCHED.get(group);
            if (last != null && level.getGameTime() < last + SEARCH_RETRY_TICKS) return List.of();
            SEARCHED.put(group, level.getGameTime());
            choose(level, group);
        }
        if (group.pit == null) return List.of();
        var out = new ArrayList<BlockPos>();
        out.add(group.pit);
        for (var side : Direction.Plane.HORIZONTAL) out.add(group.pit.relative(side));
        return out;
    }

    /**
     * A natural community's pit: an open standing cell 8 to 14 blocks from the centre, at least seven blocks from every
     * loaded member's home, preferring a hollow (banks on several sides) and lower ground. Bounded: 72 columns, 13 levels.
     */
    private static void choose(ServerLevel level, SettlementData.Group group) {
        var homes = new ArrayList<BlockPos>();
        for (var member : LoadedGroupMembers.query(level, group.id, new AABB(group.center).inflate(48, 16, 48), e -> e.isAlive())) {
            var home = member.life().homeStand();
            if (home != null) homes.add(home);
        }
        BlockPos best = null;
        double bestScore = -1e9;
        int candidates = 0;
        for (int i = 0; i < 24; i++) for (int r : new int[]{8, 11, 14}) {
            double angle = i * Math.PI / 12;
            int x = group.center.getX() + (int)Math.round(Math.cos(angle) * r), z = group.center.getZ() + (int)Math.round(Math.sin(angle) * r);
            BlockPos stand = null;
            for (int y = group.center.getY() + 6; y >= group.center.getY() - 6 && stand == null; y--) {
                var pos = new BlockPos(x, y, z);
                if (level.hasChunkAt(pos) && NaturalShelter.stand(level, pos) && level.canSeeSky(pos)) stand = pos;
            }
            if (stand == null) continue;
            final BlockPos at = stand;
            if (homes.stream().anyMatch(home -> home.distSqr(at) < 49)) continue;
            candidates++;
            int banks = 0;
            for (var side : Direction.Plane.HORIZONTAL) if (!level.getBlockState(at.relative(side)).getCollisionShape(level, at.relative(side)).isEmpty()) banks++;
            double score = Math.min(banks, 3) * 2 - (at.getY() - group.center.getY()) * .5 - Math.abs(r - 11) * .1;
            if (score > bestScore) { bestScore = score; best = at; }
        }
        group.pit = best;
        SettlementData.get(level).setDirty();
        if (best != null) GameplayLog.world(level, "settlement", "pit chosen group={} at={} center={} candidates={} score={}",
                group.id, best, group.center, candidates, bestScore);
        else GameplayLog.worldLimited(level, "settlement", "pit_none_" + group.id, 6000,
                "pit not found group={} center={} reason=no_open_ground_away_from_homes", group.id, group.center);
    }

    /**
     * A standing cell in the pit for this member, or null; the caller then uses plain ground away from home. The emptiest
     * cell is preferred, then the nearest. Refusals are logged with their reason and rate-limited per member.
     */
    public static BlockPos target(YukkuriEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        var until = REFUSED.get(entity);
        if (until != null && level.getGameTime() < until) return null;
        var group = entity.settlement().group();
        if (group == null) return null;
        var cells = cells(level, group);
        if (cells.isEmpty()) return null;
        var here = entity.blockPosition();
        var nearest = cells.stream().min(Comparator.comparingDouble(c -> c.distSqr(here))).orElseThrow();
        if (nearest.distSqr(here) > REACH * REACH) { refuse(entity, "too_far", nearest); return null; }
        var ordered = new ArrayList<BlockPos>(cells);
        ordered.sort(Comparator.<BlockPos>comparingInt(c -> waste(level, c)).thenComparingDouble(c -> c.distSqr(here)));
        int tried = 0;
        for (var cell : ordered) {
            if (!level.hasChunkAt(cell) || !entity.life().dryStand(cell)) continue;
            if (cell.distSqr(here) > NEAR * NEAR) return cell;
            if (entity.life().reachable(cell, false)) return cell;
            if (++tried >= 4) break;
        }
        refuse(entity, tried == 0 ? "no_dry_cell" : "unreachable", nearest);
        return null;
    }

    /** The member gives up on the pit for a while (unreachable, too far, path lost on the way, trip took too long). */
    public static void refuse(YukkuriEntity entity, String reason, BlockPos pit) {
        REFUSED.put(entity, entity.level().getGameTime() + REFUSE_TICKS);
        GameplayLog.limited(entity, "needs", "pit_" + reason, 600, "toilet pit refused reason={} pit={} bladder={}",
                reason, pit, entity.life().bladder());
    }

    /** True when {@code pos} is one of the group's pit cells (cleaners never pick waste up out of the pit). */
    public static boolean inPit(ServerLevel level, SettlementData.Group group, BlockPos pos) {
        for (var cell : cells(level, group)) if (cell.getX() == pos.getX() && cell.getZ() == pos.getZ() && Math.abs(cell.getY() - pos.getY()) <= 1) return true;
        return false;
    }

    private static int waste(ServerLevel level, BlockPos cell) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(Vec3.atBottomCenterOf(cell), Vec3.atBottomCenterOf(cell)).inflate(.5, .6, .5),
                item -> item.isAlive() && item.getItem().is(ModItems.WASTE.get())).size();
    }
    private GroupPits() {}
}
