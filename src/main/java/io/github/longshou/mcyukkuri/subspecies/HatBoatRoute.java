package io.github.longshou.mcyukkuri.subspecies;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.Collections;
import java.util.Comparator;

/** Bounded, loaded-chunk surface routing. A hat never gains the ability to climb a bank or waterfall. */
public final class HatBoatRoute {
    private static final int RANGE = 12, MAX_NODES = 400;

    public static boolean calmSurface(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above())) return false;
        var water = level.getFluidState(pos);
        return water.is(FluidTags.WATER) && water.getHeight(level, pos) >= .75
                && water.getFlow(level, pos).y >= -.05 && level.getFluidState(pos.above()).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }
    public static Vec3 surface(Level level, BlockPos pos) {
        return new Vec3(pos.getX()+.5, pos.getY()+level.getFluidState(pos).getHeight(level, pos)+.025, pos.getZ()+.5);
    }
    public static boolean quietSurface(Level level, BlockPos pos) {
        return calmSurface(level, pos) && level.getFluidState(pos).getFlow(level, pos).lengthSqr() < .01
                && !level.isRainingAt(pos.above());
    }
    /** Prefer quiet water near the remembered shore, without making an unreachable shortcut across land. */
    public static BlockPos restingPlace(HatBoatEntity boat, BlockPos bank) {
        return restingPlace(boat,bank,p -> true);
    }
    public static BlockPos restingPlace(HatBoatEntity boat, BlockPos bank, Predicate<Vec3> allowed) {
        var level = boat.level(); var origin = BlockPos.containing(boat.getX(), boat.getY()-.12, boat.getZ());
        var candidates = new ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(origin.offset(-6,0,-6), origin.offset(6,0,6)))
            if (quietSurface(level,p) && allowed.test(surface(level,p)) && level.getWorldBorder().isWithinBounds(p)
                    && level.noCollision(boat,boat.getBoundingBox().move(surface(level,p).subtract(boat.position())))) candidates.add(p.immutable());
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(origin)+(bank == null ? 0 : .4*p.distSqr(bank))));
        for (var p : candidates.stream().limit(8).toList())
            if (p.equals(origin) || !find(boat,surface(level,p),allowed).isEmpty()) return p;
        return null;
    }
    /** Open-water path nearest the requested target; an unreachable target is never replaced with a straight line. */
    public static List<Vec3> find(HatBoatEntity boat, Vec3 target) {
        return find(boat,target,p -> true);
    }
    /** Apply a family's allowed water area during the search, so a valid alternate route is still discoverable. */
    public static List<Vec3> find(HatBoatEntity boat, Vec3 target, Predicate<Vec3> allowed) {
        var level = boat.level(); var origin = BlockPos.containing(boat.getX(), boat.getY()-.12, boat.getZ());
        if (!calmSurface(level, origin)) return List.of();
        Map<BlockPos, BlockPos> previous = new HashMap<>(); var queue = new ArrayDeque<BlockPos>();
        previous.put(origin, origin); queue.add(origin); BlockPos best = origin;
        double distance = surface(level, origin).distanceToSqr(target);
        while (!queue.isEmpty() && previous.size() < MAX_NODES) {
            var at = queue.removeFirst();
            for (var side : Direction.Plane.HORIZONTAL) {
                var next = at.relative(side);
                if (previous.containsKey(next) || next.distSqr(origin) > RANGE*RANGE || !calmSurface(level, next)) continue;
                var point = surface(level, next);
                if (!allowed.test(point) || !level.getWorldBorder().isWithinBounds(next)
                        || !level.noCollision(boat, boat.getBoundingBox().move(point.subtract(boat.position())))) continue;
                previous.put(next, at); queue.add(next);
                double d = point.distanceToSqr(target);
                if (d < distance) { distance = d; best = next; }
            }
        }
        // Within reach of a floating meal or the water alongside a dry bank.
        if (distance > 3.25 || best.equals(origin)) return List.of();
        var path = new ArrayList<Vec3>();
        for (var at = best; !at.equals(origin); at = previous.get(at)) path.add(surface(level, at));
        Collections.reverse(path); return path;
    }
    private HatBoatRoute() {}
}
