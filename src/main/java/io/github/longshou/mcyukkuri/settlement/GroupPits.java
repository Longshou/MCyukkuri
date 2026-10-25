package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Poops;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
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
 * suitable ground away from their home. Nothing here creates waste or moves anybody: it only answers where to walk. A
 * member does not step into the pit: it stands on the bank beside the chosen pit cell with its back to it, and the
 * portion is set down on that cell (life/Poops).
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

    /** The pit cell a member's portion is meant for and the bank cell it stands on to let it go. */
    public record Spot(BlockPos pit, BlockPos stand) {}

    /**
     * Where this member relieves itself: the emptiest pit cell (full cells last), then the nearest, with a dry bank cell beside it
     * that is not itself a pit cell. Null makes the caller use plain ground away from home. Refusals are logged with their reason
     * and rate-limited per member.
     */
    public static Spot spot(YukkuriEntity entity) {
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
        ordered.sort(Comparator.<BlockPos>comparingInt(c -> Math.min(waste(level, c), Poops.CAPACITY)).thenComparingDouble(c -> c.distSqr(here)));
        // A slave lives in the pit: it lets go where it stands, on the nearest cell, instead of climbing out to the bank.
        boolean resident = entity.settlement().duties().pit().slave();
        if (resident) ordered.sort(Comparator.comparingDouble(c -> c.distSqr(here)));
        int tried = 0;
        boolean anyEdge = false;
        for (var cell : ordered) {
            if (!level.hasChunkAt(cell)) continue;
            var stand = resident ? entity.life().dryStand(cell) ? cell : null : bank(level, entity, cells, cell);
            if (stand == null) continue;
            anyEdge = true;
            if (stand.distSqr(here) > NEAR * NEAR) return new Spot(cell, stand);
            if (entity.life().reachable(stand, false)) return new Spot(cell, stand);
            if (++tried >= 4) break;
        }
        refuse(entity, !anyEdge ? "no_edge" : "unreachable", nearest);
        return null;
    }

    /** A dry cell on the bank beside {@code pit} (one block above the dip floor, or level with it), nearest to the member. */
    private static BlockPos bank(ServerLevel level, YukkuriEntity entity, List<BlockPos> cells, BlockPos pit) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (var side : Direction.Plane.HORIZONTAL) {
            var column = pit.relative(side);
            if (cells.stream().anyMatch(c -> c.getX() == column.getX() && c.getZ() == column.getZ())) continue;
            for (int rise : new int[]{1, 0, -1}) {
                var bank = column.above(rise);
                if (!level.hasChunkAt(bank) || !entity.life().dryStand(bank)) continue;
                double distance = bank.distSqr(entity.blockPosition());
                if (distance < bestDistance) { best = bank; bestDistance = distance; }
                break;
            }
        }
        return best;
    }

    /** The point on the bank cell nearest the pit that the member walks to: its rear then lies over the pit. */
    public static Vec3 edgePoint(YukkuriEntity entity, BlockPos stand, BlockPos pit) {
        double dx = Integer.signum(pit.getX() - stand.getX()), dz = Integer.signum(pit.getZ() - stand.getZ());
        // Close to the brink, yet all four corners of the body stay over the bank (SurfaceApproach.supported).
        double offset = Math.max(0, .5 - entity.getBbWidth() * .42 - .03);
        var near = Vec3.atBottomCenterOf(stand).add(dx * offset, 0, dz * offset);
        var floor = SurfaceApproach.floor(entity, near.add(0, .1, 0));
        return floor == null ? near : floor;
    }

    /** True when {@code pos} lies inside a loaded shelter's interior (droppings there stay loose items for the residents' own chores). */
    public static boolean insideShelter(ServerLevel level, BlockPos pos) {
        for (var core : LoadedShelters.within(level, pos.offset(-8, -4, -8), pos.offset(8, 4, 8))) if (core.contains(pos)) return true;
        return false;
    }

    /** The member gives up on the pit for a while (unreachable, too far, path lost on the way, trip took too long). */
    public static void refuse(YukkuriEntity entity, String reason, BlockPos pit) {
        REFUSED.put(entity, entity.level().getGameTime() + REFUSE_TICKS);
        GameplayLog.limited(entity, "needs", "pit_" + reason, 600, "toilet pit refused reason={} pit={} bladder={}",
                reason, pit, entity.life().bladder());
    }

    /** True when {@code pos} is one of the group's pit cells (cleaners never pick waste up out of the pit). */
    public static boolean inPit(ServerLevel level, SettlementData.Group group, BlockPos pos) {
        return inCells(cells(level, group), pos);
    }
    /** True when {@code pos} is in the column of one of these pit cells (one block up or down allowed). */
    public static boolean inCells(List<BlockPos> cells, BlockPos pos) {
        for (var cell : cells) if (cell.getX() == pos.getX() && cell.getZ() == pos.getZ() && Math.abs(cell.getY() - pos.getY()) <= 1) return true;
        return false;
    }

    /** Portions lying on the cell: placed ones and any loose item (a fallback drop) in it. */
    public static int waste(ServerLevel level, BlockPos cell) {
        return Poops.portions(level, cell) + level.getEntitiesOfClass(ItemEntity.class, new AABB(Vec3.atBottomCenterOf(cell), Vec3.atBottomCenterOf(cell)).inflate(.5, .6, .5),
                item -> item.isAlive() && item.getItem().is(ModItems.WASTE.get())).size();
    }
    private GroupPits() {}
}
