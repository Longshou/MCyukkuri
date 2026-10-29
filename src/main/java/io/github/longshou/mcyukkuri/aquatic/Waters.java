package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.geom.GridPath;
import io.github.longshou.mcyukkuri.motor.geom.HeightGrid;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The water a hat lives on (refactor WP6a, AQUATIC rules "沿已加载的平静水面航行，不穿岸、不走急流"): calm loaded surface cells,
 * the route a paddled hat takes over them, the bank edges beside them (where a hat is set down from or landed at, a sheltered
 * notch to moor in), a dry stand in hop reach, and a loose stick near a body. World reads only; nothing is kept.
 */
final class Waters {
    /** How far (blocks) a route, a landing or a notch is looked for around the hat; how many candidates get a route or path asked. */
    static final int RANGE = 24, ASKED = 8;
    /** What a route step beside the bank costs extra (GridPath's rim weight, in cells): the hull keeps off the shore. */
    private static final double SHORE_COST = 2;

    /** How long (ticks) an answer is kept for the same question from the same cell; how many answers are kept. */
    private static final int KEEP = 20, KEPT = 256;
    private record Kept(long tick, Object value) {}
    /** Recent answers by question (purpose, dimension, cells, sizes): bounded, never saved, no entity kept (sticks by UUID). */
    private static final Map<List<Object>, Kept> CACHE = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<List<Object>, Kept> eldest) { return size() > KEPT; }
    };

    private Waters() {}

    /** The answer to {@code key} asked within {@link #KEEP} ticks, else {@code compute}'s (null answers are kept too). */
    @SuppressWarnings("unchecked")
    private static <T> T kept(Level level, List<Object> key, Supplier<T> compute) {
        long now = level.getGameTime();
        synchronized (CACHE) {
            var k = CACHE.get(key);
            if (k != null && now >= k.tick() && now - k.tick() < KEEP) return (T) k.value();
        }
        T value = compute.get();
        synchronized (CACHE) { CACHE.put(key, new Kept(now, value)); }
        return value;
    }

    private static List<Object> key(Level level, Object... parts) {
        var out = new ArrayList<Object>(parts.length + 1);
        out.add(level.dimension());
        out.addAll(List.of(parts));
        return out;
    }

    /** Loaded still water open to the sky above: a source cell with no flow (no rapids), nothing solid or wet over it. */
    static boolean calm(Level level, BlockPos p) {
        if (!level.hasChunkAt(p) || !level.hasChunkAt(p.above())) return false;
        var water = level.getFluidState(p);
        return water.is(FluidTags.WATER) && water.isSource() && water.getFlow(level, p).lengthSqr() < 1e-4
                && level.getFluidState(p.above()).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty();
    }

    /** The water's top over a cell's centre. */
    static Vec3 surface(Level level, BlockPos p) {
        return new Vec3(p.getX() + .5, p.getY() + level.getFluidState(p).getHeight(level, p), p.getZ() + .5);
    }

    /** The calm cell a hat floats in (its origin rides at the surface), or null. */
    static BlockPos cell(PlacedHatEntity hat) {
        var p = BlockPos.containing(hat.getX(), hat.getY() - .2, hat.getZ());
        return calm(hat.level(), p) ? p : calm(hat.level(), p.above()) ? p.above() : null;
    }

    /**
     * The route from the hat's cell to {@code goal} over calm cells of the same water level only (GridPath on a one-block grid,
     * cells beside the bank cost more), as surface points without the start cell; empty when there is none. Callers ask again for
     * each leg, so a way that got blocked is planned around.
     */
    static List<Vec3> route(PlacedHatEntity hat, BlockPos goal) {
        var level = hat.level();
        var from = cell(hat);
        if (from == null || goal == null || goal.getY() != from.getY() || !calm(level, goal)) return List.of();
        return kept(level, key(level, "route", from, goal.immutable()), () -> plan(level, from, goal));
    }

    private static List<Vec3> plan(Level level, BlockPos from, BlockPos goal) {
        if (from.equals(goal)) return List.of(surface(level, goal));
        int y = from.getY();
        double x0 = from.getX() - RANGE, z0 = from.getZ() - RANGE;
        var grid = new HeightGrid(x0, z0, x0 + 2 * RANGE + 1, z0 + 2 * RANGE + 1, 1,
                (x, z) -> calm(level, BlockPos.containing(x, y, z)) ? 0 : Double.NaN);
        var path = GridPath.find(grid, from.getX() + .5, from.getZ() + .5, goal.getX() + .5, goal.getZ() + .5, .5, SHORE_COST);
        var out = new ArrayList<Vec3>();
        for (int i = 1; i < path.size(); i++) out.add(surface(level, BlockPos.containing(path.get(i)[0], y, path.get(i)[1])));
        return List.copyOf(out);
    }

    /** The next leg a paddler aims at: a few cells along the route (a straight stretch), the last one at the end. */
    static Vec3 leg(List<Vec3> route) { return route.get(Math.min(route.size() - 1, 2)); }

    /**
     * A bank edge (refactor WP6a): a dry stand, the middle of its face toward calm water, the outward normal, and the calm cell
     * in front. {@code stand(w)} is where a body of width {@code w} stands at the edge; {@code out(d)} is {@code d} blocks out
     * over the water from the edge.
     */
    record Edge(BlockPos stand, Vec3 edge, Vec3 normal, BlockPos water) {
        Vec3 stand(double width) { return edge.subtract(normal.scale(width / 2 + .05)); }
        Vec3 out(double d) { return edge.add(normal.scale(d)); }
        /** {@code d} blocks out over the water, inside the water cell: where a hat is set down. */
        Vec3 onWater(double d) { var at = out(d); return new Vec3(at.x, water.getY() + .5, at.z); }
        /** The yaw that faces the water from the stand. */
        float yaw() { return (float) (Mth.atan2(normal.z, normal.x) * Mth.RAD_TO_DEG) - 90; }
    }

    /** Every bank edge in the box around {@code at} (horizontal {@code range}, two up and down) for {@code body}, nearest first;
     *  {@code roomy} also wants the cells beside and beyond the water calm, room for a hull set down there. */
    static List<Edge> edges(YukkuriEntity body, Vec3 at, int range, boolean roomy) {
        var level = body.level();
        var out = new ArrayList<Edge>();
        var centre = BlockPos.containing(at);
        for (var p : BlockPos.betweenClosed(centre.offset(-range, -2, -range), centre.offset(range, 2, range))) {
            if (!level.hasChunkAt(p) || !body.life().dryStand(p)) continue;
            for (var d : Direction.Plane.HORIZONTAL) {
                var front = p.relative(d);
                var water = calm(level, front) ? front : calm(level, front.below()) ? front.below() : null;
                if (water == null || !level.getBlockState(front).getCollisionShape(level, front).isEmpty()) continue;
                if (roomy && !(calm(level, water.relative(d)) && calm(level, water.relative(d.getClockWise()))
                        && calm(level, water.relative(d.getCounterClockWise())))) continue;
                var n = new Vec3(d.getStepX(), 0, d.getStepZ());
                out.add(new Edge(p.immutable(), Vec3.atBottomCenterOf(p).add(n.scale(.5)), n, water.immutable()));
            }
        }
        out.sort(Comparator.comparingDouble(e -> e.edge().distanceToSqr(at)));
        return out;
    }

    /** The nearest roomy bank edge {@code body} can walk to, to set a hat down from (spec 14 WP6a SetOut), or null. */
    static Edge berth(YukkuriEntity body) {
        return kept(body.level(), key(body.level(), "berth", body.blockPosition(), body.getBbWidth()), () -> findBerth(body));
    }

    private static Edge findBerth(YukkuriEntity body) {
        int asked = 0;
        for (var e : edges(body, body.position(), 8, true)) {
            if (asked++ >= ASKED) break;
            if (SurfaceApproach.canApproach(body, e.stand(body.getBbWidth()))) return e;
        }
        return null;
    }

    /** The bank edge nearest the hat a body ashore can walk to (to reach over to it from the bank), or null. */
    static Edge beside(PlacedHatEntity hat, YukkuriEntity body) {
        return kept(body.level(), key(body.level(), "beside", hat.blockPosition(), body.blockPosition(), body.getBbWidth()), () -> findBeside(hat, body));
    }

    private static Edge findBeside(PlacedHatEntity hat, YukkuriEntity body) {
        int asked = 0;
        for (var e : edges(body, hat.position(), 4, false)) {
            if (asked++ >= ASKED) break;
            var stand = e.stand(body.getBbWidth());
            if (SurfaceApproach.arrived(body, stand) || SurfaceApproach.canApproach(body, stand)) return e;
        }
        return null;
    }

    /** The bank edge nearest the hat whose calm cell in front the hat can be paddled to, not one of {@code tried}, or null (a
     *  landing, spec WP6a ComeAshore). */
    static Edge landing(PlacedHatEntity hat, YukkuriEntity owner, Set<BlockPos> tried) {
        var from = cell(hat);
        return kept(owner.level(), key(owner.level(), "landing", from == null ? hat.blockPosition() : from, Set.copyOf(tried)),
                () -> findLanding(hat, owner, tried));
    }

    private static Edge findLanding(PlacedHatEntity hat, YukkuriEntity owner, Set<BlockPos> tried) {
        int asked = 0;
        for (var e : edges(owner, hat.position(), 12, false)) {
            if (tried.contains(e.stand())) continue;
            if (asked++ >= ASKED) break;
            if (!route(hat, e.water()).isEmpty()) return e;
        }
        return null;
    }

    /** A dry stand by the edges near {@code body} that a hop from where it is lands on (world), or null. */
    static Vec3 ashore(YukkuriEntity body) {
        var at = body.position();
        return kept(body.level(), key(body.level(), "ashore", Math.round(at.x * 4), Math.round(at.y * 4), Math.round(at.z * 4), body.getBbWidth()),
                () -> findAshore(body));
    }

    private static Vec3 findAshore(YukkuriEntity body) {
        int asked = 0;
        for (var e : edges(body, body.position(), 2, false)) {
            if (asked++ >= ASKED) break;
            var at = e.stand(body.getBbWidth());
            if (new Hop(at).check(body).ok()) return at;
        }
        return null;
    }

    /**
     * A sheltered calm cell to moor in near the hat, reached over the water (AQUATIC 无绳夜泊: a bank notch or a covered spot): the
     * one with the most land around it (at least two of four sides) or a roof, nearest first; null when there is none.
     */
    static BlockPos notch(PlacedHatEntity hat) {
        var level = hat.level();
        var from = cell(hat);
        if (from == null) return null;
        return kept(level, key(level, "notch", from), () -> findNotch(hat, from));
    }

    private static BlockPos findNotch(PlacedHatEntity hat, BlockPos from) {
        var level = hat.level();
        var found = new ArrayList<BlockPos>();
        for (var p : BlockPos.betweenClosed(from.offset(-16, 0, -16), from.offset(16, 0, 16)))
            if (calm(level, p) && shelter(level, p) >= 2) found.add(p.immutable());
        found.sort(Comparator.comparingDouble(p -> -shelter(level, p) * 64 + p.distSqr(from)));
        int asked = 0;
        for (var p : found) {
            if (asked++ >= ASKED) break;
            if (p.equals(from) || !route(hat, p).isEmpty()) return p;
        }
        return null;
    }

    /** Land around a calm cell (sides that are not calm water) plus two for a roof over it. */
    static int shelter(Level level, BlockPos p) {
        int sides = 0;
        for (var d : Direction.Plane.HORIZONTAL) if (!calm(level, p.relative(d))) sides++;
        return sides + (level.canSeeSky(p.above()) ? 0 : 2);
    }

    /** A calm cell {@code min}..{@code max} blocks from the hat that it can be paddled to, picked at random, or null. */
    static BlockPos wander(PlacedHatEntity hat, int min, int max) {
        var from = cell(hat);
        if (from == null) return null;
        var random = hat.getRandom();
        for (int i = 0; i < 4; i++) {
            double a = random.nextDouble() * Math.PI * 2, r = min + random.nextDouble() * (max - min);
            var p = BlockPos.containing(from.getX() + .5 + Math.cos(a) * r, from.getY(), from.getZ() + .5 + Math.sin(a) * r);
            if (calm(hat.level(), p) && shelter(hat.level(), p) == 0 && !route(hat, p).isEmpty()) return p;
        }
        return null;
    }

    /** The loose stick nearest {@code body} within {@code range} on dry ground; with {@code walk}, one it can walk to now. */
    static ItemEntity stick(YukkuriEntity body, double range, boolean walk) {
        UUID id = kept(body.level(), key(body.level(), "stick", body.blockPosition(), range, walk), () -> {
            var found = findStick(body, range, walk);
            return found == null ? null : found.getUUID();
        });
        return id != null && body.level() instanceof ServerLevel server && server.getEntity(id) instanceof ItemEntity item && item.isAlive()
                && !item.getItem().isEmpty() ? item : null;
    }

    private static ItemEntity findStick(YukkuriEntity body, double range, boolean walk) {
        var sticks = body.level().getEntitiesOfClass(ItemEntity.class, new AABB(body.blockPosition()).inflate(range),
                item -> item.isAlive() && item.getItem().is(Items.STICK) && !item.isInWater());
        sticks.sort(Comparator.comparingDouble(body::distanceToSqr));
        for (int i = 0; i < sticks.size() && i < 3; i++) {
            var at = SurfaceApproach.floor(body, sticks.get(i).position());
            if (at != null && (!walk || SurfaceApproach.canApproach(body, at))) return sticks.get(i);
        }
        return null;
    }

    /** Where a hat of {@code owner}'s set down at this edge floats: its hull's box just clear of the bank, the brim reaching over
     *  the edge (the old rule's near brim under the bank), so a hop aboard and a reach from the bank are short. */
    static Vec3 hatAt(Edge edge, YukkuriEntity owner) {
        return edge.onWater(ModEntities.PLACED_HAT.get().getWidth() * HatScale.of(owner) / 2 + .05);
    }

    /** {@code [aquatic] route} once a leg is planned (limited) or refused. */
    static void log(PlacedHatEntity hat, YukkuriEntity owner, BlockPos goal, List<Vec3> route, String purpose) {
        var from = hat.blockPosition();
        if (route.isEmpty()) GameplayLog.limited(owner, "aquatic", "route.none." + purpose, 200, "route none purpose={} from={} to={} reason=no_calm_water_path",
                purpose, from, goal == null ? "none" : goal);
        else GameplayLog.limited(owner, "aquatic", "route." + purpose, 100, "route purpose={} from={} to={} cells={} leg={}", purpose,
                from, goal, route.size(), leg(route));
    }
}
