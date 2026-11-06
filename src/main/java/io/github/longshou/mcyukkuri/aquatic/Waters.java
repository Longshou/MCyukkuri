package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatBehavior;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.HullSurface;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.geom.GridPath;
import io.github.longshou.mcyukkuri.motor.geom.HeightGrid;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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
 * The water a hat lives on (AQUATIC rules "沿已加载的平静水面航行，不穿岸、不走急流"): calm loaded surface cells,
 * the route a paddled hat takes over them, the bank edges beside them (where a hat is set down from or landed at), a dry stand in hop reach, and a loose stick near a body. World reads only; nothing is kept.
 */
final class Waters {
    /** How far (blocks) a route or a landing is looked for around the hat; how many candidates get a route or path asked. */
    static final int RANGE = 24, ASKED = 8;
    /** Brim stands closer than this (blocks) are one stand for a landing search: the difference is invisible. */
    private static final double SAME_STAND = 0.15;
    /** Ticks a landing search that found nothing stays unasked. */
    private static final int NONE_HOLD = 4;
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
    private static <T> T kept(Level level, List<Object> key, Supplier<T> compute) {
        return kept(level, key, KEEP, compute);
    }

    @SuppressWarnings("unchecked")
    private static <T> T kept(Level level, List<Object> key, int ticks, Supplier<T> compute) {
        long now = level.getGameTime();
        synchronized (CACHE) {
            var k = CACHE.get(key);
            if (k != null && now >= k.tick() && now - k.tick() < ticks) return (T) k.value();
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

    /** The calm cell a floating body or point is in (just under it, or the one above), or null. */
    static BlockPos cell(Level level, Vec3 at) {
        var p = BlockPos.containing(at.x, at.y - .2, at.z);
        return calm(level, p) ? p : calm(level, p.above()) ? p.above() : null;
    }

    /** The calm cell a hat floats in (its origin rides at the surface), or null. */
    static BlockPos cell(PlacedHatEntity hat) {
        var p = BlockPos.containing(hat.getX(), hat.getY() - .2, hat.getZ());
        return calm(hat.level(), p) ? p : calm(hat.level(), p.above()) ? p.above() : null;
    }

    /**
     * The route from the hat's cell to {@code goal} over calm cells of the same water level only (GridPath on a one-block grid,
     * cells beside the bank cost more, another set-down hull's brim is no water at all), as surface points without the start
     * cell; empty when there is none. Callers ask again for each leg, so a way that got blocked is planned around.
     */
    static List<Vec3> route(PlacedHatEntity hat, BlockPos goal) {
        var level = hat.level();
        var from = cell(hat);
        if (from == null || goal == null || goal.getY() != from.getY() || !calm(level, goal)) return List.of();
        return kept(level, key(level, "route", from, goal.immutable()), () -> plan(hat, from, goal));
    }

    private static List<Vec3> plan(PlacedHatEntity hat, BlockPos from, BlockPos goal) {
        if (from.equals(goal)) return List.of(surface(hat.level(), goal));
        var level = hat.level();
        int y = from.getY();
        var taken = hulls(hat, from, RANGE);
        double x0 = from.getX() - RANGE, z0 = from.getZ() - RANGE;
        var grid = new HeightGrid(x0, z0, x0 + 2 * RANGE + 1, z0 + 2 * RANGE + 1, 1, (x, z) -> {
            var p = BlockPos.containing(x, y, z);
            return calm(level, p) && (p.getX() == from.getX() && p.getZ() == from.getZ() || !hullIn(taken, p)) ? 0 : Double.NaN;
        });
        var path = GridPath.find(grid, from.getX() + .5, from.getZ() + .5, goal.getX() + .5, goal.getZ() + .5, .5, SHORE_COST);
        var out = new ArrayList<Vec3>();
        for (int i = 1; i < path.size(); i++) out.add(surface(level, BlockPos.containing(path.get(i)[0], y, path.get(i)[1])));
        return List.copyOf(out);
    }

    /** The brims of the other set-down hulls near {@code at}, widened by {@code hat}'s own brim: the water its hull cannot paddle through. */
    private static List<AABB> hulls(PlacedHatEntity hat, BlockPos at, int range) {
        double room = HullBounds.RADIUS * hat.hullScale() / HullBounds.SCALE;
        var centre = Vec3.atCenterOf(at);
        return hat.level().getEntitiesOfClass(PlacedHatEntity.class, new AABB(centre, centre).inflate(range + 2), other -> other != hat)
                .stream().map(other -> other.getBoundingBox().inflate(room, 0, room)).toList();
    }

    private static boolean hullIn(List<AABB> hulls, BlockPos p) {
        return hulls.stream().anyMatch(box -> box.minX <= p.getX() + 1 && box.maxX >= p.getX() && box.minZ <= p.getZ() + 1 && box.maxZ >= p.getZ());
    }

    /** The next leg a paddler aims at: a few cells along the route (a straight stretch), the last one at the end. */
    static Vec3 leg(List<Vec3> route) { return route.get(Math.min(route.size() - 1, 2)); }

    /**
     * A bank edge: a dry stand, the middle of its face toward calm water, the outward normal, and the calm cell
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

    /** The nearest free berth the parent can approach and hop aboard from; children are loaded from the bank. */
    static Edge berth(YukkuriEntity body) {
        return kept(body.level(), key(body.level(), "berth", body.blockPosition(), body.getBbWidth()), () -> findBerth(body));
    }

    private static Edge findBerth(YukkuriEntity body) {
        for (var e : freeEdges(body, body.position())) {
            var stand = boardingStand(e, body);
            if (!SurfaceApproach.canApproach(body, stand)
                    || !PlacedHatBehavior.waterFits(body.level(), hatAt(e, body), HatGeometry.wornForm(body), HatScale.of(body))) continue;
            var frame = HullAfloat.frame(body.level(), hatAt(e, body), HatScale.of(body), e.yaw() + (float) HullBounds.BOARDING_YAW);
            if (Hop.entry(body, stand, frame, HullSurface.hopStops(body, HatScale.of(body))) != null) return e;
        }
        return null;
    }

    /** The nearest roomy bank edge {@code body} can walk to and hop out from over the water, nothing placed there, or null (the
     *  swim fallback of a reclaim, which needs no hop to a seat). */
    static Edge shore(YukkuriEntity body) {
        return kept(body.level(), key(body.level(), "shore", body.blockPosition(), body.getBbWidth()), () -> findShore(body));
    }

    private static Edge findShore(YukkuriEntity body) {
        for (var e : freeEdges(body, body.position())) if (SurfaceApproach.canApproach(body, e.stand(body.getBbWidth()))) return e;
        return null;
    }

    /** How far (blocks) an owner ashore looks for water to set out from when none is within her berth reach. */
    static final int FAR = 32;

    /** The nearest roomy bank edge within {@link #FAR} blocks that {@code body} can walk to and whose water carries a hull, not one
     *  it already stands at or is in {@code tried}, or null: where an owner too far from any water heads to set out. Kept per
     *  8-block cell for 200 ticks. */
    static Edge farShore(YukkuriEntity body, Set<BlockPos> tried) {
        var at = body.blockPosition();
        return kept(body.level(), key(body.level(), "far", at.getX() >> 3, at.getZ() >> 3, body.getBbWidth(), Set.copyOf(tried)), 200,
                () -> findFarShore(body, tried));
    }

    private static Edge findFarShore(YukkuriEntity body, Set<BlockPos> tried) {
        int asked = 0;
        for (var e : edges(body, body.position(), FAR, true)) {
            var stand = e.stand(body.getBbWidth());
            if (tried.contains(e.stand()) || stand.distanceToSqr(body.position()) < 9 || !floats(e, body)) continue;
            if (asked++ >= ASKED) break;
            if (SurfaceApproach.canApproach(body, stand)) return e;
        }
        return null;
    }

    /** The roomy edges round {@code body} with no other hat over the water they front, nearest first, up to {@link #ASKED}. */
    private static List<Edge> freeEdges(YukkuriEntity body, Vec3 at) {
        var out = new ArrayList<Edge>();
        for (var e : edges(body, at, 8, true)) {
            if (out.size() >= ASKED) break;
            if (!hatOver(body, hatAt(e, body), HatGeometry.wornForm(body).brim()[1] * HatScale.of(body) / 16)) out.add(e);
        }
        return out;
    }

    /** A brim place of the hull a body rides to set its own hat down from: {@code stop} in the hull's local units, {@code edge} the
     *  hull's rim there, as the bank edge the hat is set down beside. */
    record Launch(Vec3 stop, Edge edge) {}

    /** Under this speed (blocks per tick) a hull lies still enough for a hat to be set beside it and a hop to plan from it. */
    private static final double MOORED = .005;

    /** The hull lies still: a young leaves it for the bank only then, not while it still glides into the bank under the hop. */
    static boolean moored(PlacedHatEntity hat) { return hat.getDeltaMovement().horizontalDistanceSqr() <= MOORED * MOORED; }

    /**
     * Where {@code body}, standing on the hull {@code ride}, can set its own hat down on calm water beside the hull and hop onto it,
     * or null: a brim place it stands on or hops to whose rim fronts water that carries a hat of its size with no other hull over
     * it, a supported turn there to face the water, and a hop from there onto the brim of the hat set down (as a berth is judged).
     */
    static Launch brimLaunch(YukkuriEntity body, PlacedHatEntity ride) {
        return kept(body.level(), key(body.level(), "launch", ride.getUUID(), ride.blockPosition(), body.getUUID()), () -> findLaunch(body, ride));
    }

    private static Launch findLaunch(YukkuriEntity body, PlacedHatEntity ride) {
        var water = cell(ride);
        if (water == null || body.getVehicle() != ride || !moored(ride)) return null;
        var level = body.level();
        float scale = HatScale.of(body);
        var form = HatGeometry.wornForm(body);
        var stops = HullSurface.hopStops(body, scale);
        var here = ride.riders().local(body.getUUID());
        int asked = 0;
        for (var stop : ride.hopStops(body).stream().sorted(Comparator.comparingDouble(p -> p.distanceToSqr(here))).toList()) {
            var from = ride.toWorld(stop);
            var away = from.subtract(ride.position()).multiply(1, 0, 1).normalize();
            var rim = ride.position().add(away.scale(HullAfloat.brimToward(ride, from)));
            var edge = new Edge(BlockPos.containing(from), new Vec3(rim.x, from.y, rim.z), away, water);
            var at = hatAt(edge, body);
            if (!calm(level, BlockPos.containing(at)) || !PlacedHatBehavior.waterFits(level, at, form, scale)
                    || hatOver(body, at, form.brim()[1] * scale / 16)) continue;
            if (asked++ >= ASKED) break;
            if (Floor.turnHeight(ride, body, stop, body.yBodyRot - ride.getYRot(), edge.yaw() - ride.getYRot()).isEmpty()
                    || !standsAt(ride, body, stop) && !Hop.reaches(body, ride, stop)) continue;
            var frame = HullAfloat.frame(level, at, scale, edge.yaw() + (float) HullBounds.BOARDING_YAW);
            if (Hop.entry(body, from, frame, stops) != null) return new Launch(stop, edge);
        }
        return null;
    }

    /** {@code body} already stands on the brim place {@code stop} of the hull it rides. */
    static boolean standsAt(PlacedHatEntity ride, YukkuriEntity body, Vec3 stop) {
        return stop.subtract(ride.riders().local(body.getUUID())).multiply(1, 0, 1).lengthSqr() < 1e-3;
    }

    /** The bank edge nearest {@code hat} from which {@code body} could really reach it: a stand it can walk to and either touch the
     *  hat from (mouth or braid, sight free) or stroke its water (the action's own check, facing it). One scan for admission and for
     *  the steps that run from the stand. */
    static Edge bankEdge(YukkuriEntity body, PlacedHatEntity hat) {
        int asked = 0;
        for (var e : edges(body, hat.position(), 4, false)) {
            var stand = e.stand(body.getBbWidth());
            if (!bankReach(body, stand, hat)) continue;
            if (asked++ >= ASKED) break;
            if (SurfaceApproach.arrived(body, stand) || SurfaceApproach.canApproach(body, stand)) return e;
        }
        return null;
    }

    /** The bank's actual closing methods: take the hat directly or draw it in with a reachable braid stroke. */
    private static boolean bankReach(YukkuriEntity body, Vec3 stand, PlacedHatEntity hat) {
        float yaw = SurfaceApproach.yaw(stand, hat.position());
        return ContactGeometry.reachesHullFrom(body, stand, yaw, hat, true, true) || new Beckon(hat).checkFrom(body, stand, yaw).ok();
    }

    /** The bank edge nearest the hat a body ashore can walk to (to reach over to it from the bank), or null. */
    static Edge beside(PlacedHatEntity hat, YukkuriEntity body) {
        return kept(body.level(), key(body.level(), "beside", hat.blockPosition(), body.blockPosition(), body.getBbWidth()), () -> findBeside(hat, body));
    }

    private static Edge findBeside(PlacedHatEntity hat, YukkuriEntity body) {
        int asked = 0;
        for (var e : edges(body, hat.position(), 4, false)) {
            if (asked++ >= ASKED) break;
            var stand = boardingStand(e, body);
            if (SurfaceApproach.arrived(body, stand) || SurfaceApproach.canApproach(body, stand)) return e;
        }
        return null;
    }

    /** The bank edge nearest the hat whose calm cell in front the hat can be paddled to, not one of {@code tried}, or null (a
     *  landing, ComeAshore). */
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

    /** A dry stand by the edges near {@code body}, under no other hat's brim, that a hop from where it is lands on (world), or null. */
    static Vec3 ashore(YukkuriEntity body) { return ashore(body, body.position()); }

    record Exit(Vec3 brim, Vec3 bank) {}

    /** The local brim spot of {@code hat} and dry stand one hop away for {@code body}, or null: the ring spot nearest
     *  the bank first, then the ones beside it, so a hull whose brim reaches over the bank still has a way off. */
    static Exit brimAshore(PlacedHatEntity hat, YukkuriEntity body) {
        var level = body.level();
        // A search that found no way off is not repeated every tick while the hull drifts: a few ticks' wait is not visible.
        var none = key(level, "brim_none", hat.getUUID(), body.getUUID());
        if (kept(level, none, NONE_HOLD, () -> Boolean.FALSE) == Boolean.TRUE) return null;
        var exit = kept(level, key(level, "brim", hat.getUUID(), body.getUUID(), hat.frame(), body.position()), 1,
                () -> timed(body, "brim_ashore", () -> findBrimAshore(hat, body)));
        if (exit == null) synchronized (CACHE) { CACHE.put(none, new Kept(level.getGameTime(), Boolean.TRUE)); }
        return exit;
    }

    private static Exit findBrimAshore(PlacedHatEntity hat, YukkuriEntity body) {
        var edge = edges(body, hat.position(), 4, false).stream().findFirst().orElse(null);
        if (edge == null) return null;
        Vec3 nearest = null;
        var search = Hop.Search.admission();
        var refused = new ArrayList<Vec3>();
        for (var local : search.stops(hat, body).stream()
                .sorted(Comparator.comparingDouble(p -> hat.toWorld(p).distanceToSqr(edge.edge()))).toList()) {
            var from = hat.toWorld(local);
            if (nearest == null) nearest = from;
            // Stands a few centimetres apart fail alike; once one has no way ashore, its close neighbours are not asked again.
            if (refused.stream().anyMatch(r -> r.distanceToSqr(from) < SAME_STAND * SAME_STAND)) continue;
            var bank = findAshore(body, from, at -> search.admits(body, hat, local, at));
            if (bank != null) return new Exit(local, bank);
            refused.add(from);
        }
        GameplayLog.limited(body, "aquatic", "hop.ashore", 200,
                "hop ashore unavailable hat={} at={} edge={} brim={} stand={} exit_stops=0 width={} ability={}",
                hat.getUUID(), GameplayLog.at(hat.position()), GameplayLog.at(edge.edge()), GameplayLog.at(nearest),
                GameplayLog.at(boardingStand(edge, body)), body.getBbWidth(), BodyAbility.of(body).detail());
        return null;
    }

    /** A dry stand within one hop. An unsailed owner must also be able to collect her hat without swimming after it. */
    static Vec3 ashore(YukkuriEntity body, Vec3 from) {
        return kept(body.level(), key(body.level(), "ashore", body.getUUID(), body.position(), from, body.getBbWidth()), 1,
                () -> timed(body, "ashore", () -> findAshore(body, from)));
    }

    private static <T> T timed(YukkuriEntity body, String operation, Supplier<T> search) {
        long started = System.nanoTime();
        T result = search.get();
        double millis = (System.nanoTime() - started) / 1_000_000.0;
        if (StatusEvents.trace() && millis >= 50) GameplayLog.event(body, "aquatic",
                "landing search operation={} millis={} found={}", operation, millis, result != null);
        return result;
    }

    private static Vec3 findAshore(YukkuriEntity body, Vec3 from) {
        return findAshore(body, from, at -> Hop.reaches(body, from, at));
    }

    private static Vec3 findAshore(YukkuriEntity body, Vec3 from, Predicate<Vec3> reachable) {
        var own = body.getVehicle() instanceof PlacedHatEntity hat && hat.owner() == body ? hat : null;
        int asked = 0;
        for (var e : edges(body, from, 2, false)) {
            var at = Hop.bankStand(e.edge(), e.normal(), from);
            if (asked++ >= ASKED) break;
            String refusal = !Hop.ground(body, at) ? "no_support" : hatOver(body, at, 0) ? "other_hat"
                    : own != null && !body.childhood().canSail() && !bankReach(body, e.stand(body.getBbWidth()), own) ? "reclaim_reach"
                    : !reachable.test(at) ? "hop_arc" : null;
            if (refusal == null) return at;
            GameplayLog.limited(body, "aquatic", "shore." + refusal, 200,
                    "hop shore stand refused reason={} from={} stand={}", refusal, GameplayLog.at(from), GameplayLog.at(at));
        }
        return null;
    }

    /** Some set-down hat other than the one {@code body} rides has its brim over {@code at}, or within {@code margin} of it: a hop
     *  there comes down on that hat, a hat set down there would lie in it. */
    private static boolean hatOver(YukkuriEntity body, Vec3 at, double margin) {
        return !body.level().getEntitiesOfClass(PlacedHatEntity.class, new AABB(at, at).inflate(2 + margin), h -> h != body.getVehicle()
                && h.position().subtract(at).horizontalDistance() < HullAfloat.brimToward(h, at) + margin).isEmpty();
    }

    /** Land around a calm cell (sides that are not calm water) plus two for a roof over it. */
    static int shelter(Level level, BlockPos p) {
        int sides = 0;
        for (var d : Direction.Plane.HORIZONTAL) if (!calm(level, p.relative(d))) sides++;
        return sides + (level.canSeeSky(p.above()) ? 0 : 2);
    }

    /** A calm cell {@code min}..{@code max} blocks from the hat that it can be paddled to, picked at random among the open water
     *  on that ring, or null. */
    static BlockPos wander(PlacedHatEntity hat, int min, int max) {
        var from = cell(hat);
        if (from == null) return null;
        var level = hat.level();
        var random = hat.getRandom();
        var open = new ArrayList<BlockPos>();
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, r = min + random.nextDouble() * (max - min);
            var p = BlockPos.containing(from.getX() + .5 + Math.cos(a) * r, from.getY(), from.getZ() + .5 + Math.sin(a) * r);
            if (!open.contains(p) && calm(level, p) && shelter(level, p) == 0) open.add(p);
        }
        while (!open.isEmpty()) {
            var p = open.remove(random.nextInt(open.size()));
            if (!route(hat, p).isEmpty()) return p;
        }
        return null;
    }

    /** The loose stick nearest {@code body} within {@code range} that it can take from where it stands: lying on dry ground or, for a
     *  body on foot ({@code walk}: one it can walk to now), afloat beside a bank stand within its reach. An owner afloat looks
     *  for sticks on the water with {@link #floatingStick}. */
    static ItemEntity stick(YukkuriEntity body, double range, boolean walk) {
        UUID id = kept(body.level(), key(body.level(), "stick", body.blockPosition(), range, walk), () -> {
            var found = findStick(body, range, walk);
            return found == null ? null : found.getUUID();
        });
        return id != null && body.level() instanceof ServerLevel server && server.getEntity(id) instanceof ItemEntity item && item.isAlive()
                && !item.getItem().isEmpty() ? item : null;
    }

    private static ItemEntity findStick(YukkuriEntity body, double range, boolean walk) {
        var search = new AABB(body.blockPosition()).inflate(range);
        // Observe one extra search-width outside the pickup box, without admitting those sticks as candidates.
        var sticks = body.level().getEntitiesOfClass(ItemEntity.class, search.inflate(range), item -> {
            if (!item.isAlive() || !item.getItem().is(Items.STICK)) return false;
            String reason = !search.intersects(item.getBoundingBox()) ? "out_of_range" : item.isInWater() && !walk ? "wet" : null;
            if (reason == null) return true;
            rejectStick(body, item, reason, null, range, walk);
            return false;
        });
        sticks.sort(Comparator.comparingDouble(body::distanceToSqr));
        for (int i = 0; i < sticks.size() && i < 3; i++) {
            var stick = sticks.get(i);
            boolean afloat = stick.isInWater();
            var at = afloat ? SurfaceApproach.beside(body, stick, body.getBbWidth() / 2 + .2, 1.2) : SurfaceApproach.floor(body, stick.position());
            if (at != null && (!walk || afloat || SurfaceApproach.canApproach(body, at))) return stick;
            rejectStick(body, stick, at == null ? "no_dry_floor" : "unreachable", at, range, walk);
        }
        return null;
    }

    private static void rejectStick(YukkuriEntity body, ItemEntity stick, String reason, Vec3 floor, double range, boolean walk) {
        GameplayLog.limited(body, "aquatic", "stick_" + reason + "_" + stick.getId(), 200,
                "stick rejected id={} uuid={} at={} reason={} floor={} range={} walk={} pickupDelayed={}", stick.getId(), stick.getUUID(),
                GameplayLog.at(stick.position()), reason, GameplayLog.at(floor), range, walk, stick.hasPickUpDelay());
    }

    /**
     * Food to fetch from the hat (AQUATIC 补给: 先寻找可达漂浮食物和岸缘食源): an item {@code body} would eat, afloat in calm water or
     * lying at the edge of it, within {@code range} of the hat, whose water the hat can be paddled to; nearest first, or null.
     */
    static ItemEntity food(PlacedHatEntity hat, YukkuriEntity body, int range) {
        return loose(hat, "food", body.getUUID(), range, item -> item.getItem().is(YukkuriEntity.FOOD) && body.life().acceptsFood(item.getItem()));
    }

    /** A stick afloat in calm water or lying at the edge of it within {@code range} of the hat, whose water the hat can be paddled to:
     *  what an owner with no stick (never had one, or lost it) goes for. Nearest first, or null. */
    static ItemEntity floatingStick(PlacedHatEntity hat, int range) {
        return loose(hat, "stick", "", range, item -> item.getItem().is(Items.STICK));
    }

    private static ItemEntity loose(PlacedHatEntity hat, String what, Object who, int range, Predicate<ItemEntity> wanted) {
        var level = hat.level();
        // the item she chose stays her target while it still exists in sight, even bobbing under the surface or drifting off its cell
        var chosenKey = key(level, "chosen", what, who);
        UUID chosen;
        synchronized (CACHE) { var k = CACHE.get(chosenKey); chosen = k == null ? null : (UUID) k.value(); }
        if (chosen != null && level instanceof ServerLevel server && server.getEntity(chosen) instanceof ItemEntity item && item.isAlive()
                && !item.getItem().isEmpty() && wanted.test(item) && hat.getBoundingBox().inflate(range, 3, range).intersects(item.getBoundingBox())) return item;
        var from = cell(hat);
        UUID id = kept(level, key(level, what, who, from == null ? hat.blockPosition() : from, range), () -> {
            var found = findLoose(hat, range, wanted);
            return found == null ? null : found.getUUID();
        });
        synchronized (CACHE) { if (id == null) CACHE.remove(chosenKey); else CACHE.put(chosenKey, new Kept(level.getGameTime(), id)); }
        return id != null && level instanceof ServerLevel server && server.getEntity(id) instanceof ItemEntity item && item.isAlive()
                && !item.getItem().isEmpty() ? item : null;
    }

    private static ItemEntity findLoose(PlacedHatEntity hat, int range, Predicate<ItemEntity> wanted) {
        var level = hat.level();
        var from = cell(hat);
        var items = level.getEntitiesOfClass(ItemEntity.class, hat.getBoundingBox().inflate(range, 3, range),
                item -> item.isAlive() && !item.hasPickUpDelay() && wanted.test(item));
        items.sort(Comparator.comparingDouble(hat::distanceToSqr));
        int asked = 0;
        for (var item : items) {
            var water = foodCell(level, item);
            if (water == null) continue;
            if (asked++ >= ASKED) break;
            if (water.equals(from) || !route(hat, water).isEmpty()) return item;
        }
        return null;
    }

    /** The calm cell a food item floats in, or one beside the dry stand it lies on; null when it is nowhere near calm water. */
    static BlockPos foodCell(Level level, ItemEntity item) {
        var p = item.blockPosition();
        if (calm(level, p)) return p;
        if (calm(level, p.below())) return p.below();
        for (var d : Direction.Plane.HORIZONTAL) {
            var q = p.relative(d);
            if (calm(level, q)) return q;
            if (calm(level, q.below())) return q.below();
        }
        return null;
    }

    /** The hull's collision box clears the bank while its brim reaches over the edge. */
    static Vec3 hatAt(Edge edge, YukkuriEntity owner) {
        return edge.onWater(HullBounds.RADIUS * HatScale.of(owner) / HullBounds.SCALE + .05);
    }

    static Vec3 boardingStand(Edge edge, YukkuriEntity body) {
        return edge.edge().subtract(edge.normal().scale(body.getBbWidth() / 2 + .025));
    }

    /** Whether the water in front of {@code edge} is deep enough to carry a hull of {@code body}'s size (never set one down where it
     *  would rest on the floor and become its item). */
    static boolean floats(Edge edge, YukkuriEntity body) {
        return HullAfloat.floats(body.level(), hatAt(edge, body), HatScale.of(body));
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
