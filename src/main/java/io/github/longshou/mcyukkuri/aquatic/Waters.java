package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.HatSeats;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.geom.GridPath;
import io.github.longshou.mcyukkuri.motor.geom.HeightGrid;
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
 * The water a hat lives on (AQUATIC rules "沿已加载的平静水面航行，不穿岸、不走急流"): calm loaded surface cells,
 * the route a paddled hat takes over them, the bank edges beside them (where a hat is set down from or landed at), a dry stand in hop reach, and a loose stick near a body. World reads only; nothing is kept.
 */
final class Waters {
    /** How far (blocks) a route or a landing is looked for around the hat; how many candidates get a route or path asked. */
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

    /** The nearest roomy bank edge {@code body} can walk to, place a hat from and hop to its planned captain seat, with every
     *  fetched ward within that seat's braid reach and no other hat over the hull, or null. */
    static Edge berth(YukkuriEntity body, List<YukkuriEntity> wards) {
        var cells = wards.stream().map(y -> List.of(y.getUUID(), y.blockPosition())).toList();
        return kept(body.level(), key(body.level(), "berth", body.blockPosition(), body.getBbWidth(), cells), () -> findBerth(body, wards));
    }

    /** Whether every ward is within braid reach of the captain seat planned at this berth. */
    static boolean berthBraidReach(YukkuriEntity body, Edge edge, List<YukkuriEntity> wards) {
        var at = hatAt(edge, body);
        var captain = HatSeats.captain(at, HatScale.of(body) / 16.0, edge.yaw() + (float) HullBounds.BOARDING_YAW);
        return wards.stream().allMatch(y -> ContactGeometry.braidGap(body, captain, y) <= 0);
    }

    private static Edge findBerth(YukkuriEntity body, List<YukkuriEntity> wards) {
        for (var e : freeEdges(body)) {
            if (!floats(e, body)) continue;
            var stand = e.stand(body.getBbWidth());
            if (!SurfaceApproach.canApproach(body, stand)) continue;
            var captain = HatSeats.captain(hatAt(e, body), HatScale.of(body) / 16.0, e.yaw() + (float) HullBounds.BOARDING_YAW);
            if (Hop.reaches(body, stand, captain) && berthBraidReach(body, e, wards)) return e;
        }
        return null;
    }

    /** The nearest roomy bank edge {@code body} can walk to and hop out from over the water, nothing placed there, or null (the
     *  swim fallback of a reclaim, which needs no hop to a seat). */
    static Edge shore(YukkuriEntity body) {
        return kept(body.level(), key(body.level(), "shore", body.blockPosition(), body.getBbWidth()), () -> findShore(body));
    }

    private static Edge findShore(YukkuriEntity body) {
        for (var e : freeEdges(body)) if (SurfaceApproach.canApproach(body, e.stand(body.getBbWidth()))) return e;
        return null;
    }

    /** The roomy edges round {@code body} with no other hat over the water they front, nearest first, up to {@link #ASKED}. */
    private static List<Edge> freeEdges(YukkuriEntity body) {
        var out = new ArrayList<Edge>();
        for (var e : edges(body, body.position(), 8, true)) {
            if (out.size() >= ASKED) break;
            if (!hatOver(body, hatAt(e, body), HatGeometry.wornForm(body).brim()[1] * HatScale.of(body) / 16)) out.add(e);
        }
        return out;
    }

    /** The bank edge nearest {@code hat} from which {@code body} could really reach it: a stand it can walk to and either touch the
     *  hat from (mouth or braid, sight free) or stroke its water (the action's own check, facing it). One scan for admission and for
     *  the steps that run from the stand. */
    static Edge bankEdge(YukkuriEntity body, PlacedHatEntity hat) {
        int asked = 0;
        for (var e : edges(body, hat.position(), 4, false)) {
            var stand = e.stand(body.getBbWidth());
            if (!ContactGeometry.reachesFrom(body, stand, hat, true)
                    && !new Beckon(hat).checkFrom(body, stand, SurfaceApproach.yaw(stand, hat.position())).ok()) continue;
            if (asked++ >= ASKED) break;
            if (SurfaceApproach.arrived(body, stand) || SurfaceApproach.canApproach(body, stand)) return e;
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
            var stand = boardingStand(e, hat, body);
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

    /** The local brim spot of {@code hat} from which a dry stand is one hop away for {@code body}, or null: the ring spot nearest
     *  the bank first, then the ones beside it, so a hull whose brim reaches over the bank still has a way off (kept for a few ticks). */
    static Vec3 brimAshore(PlacedHatEntity hat, YukkuriEntity body) {
        return kept(hat.level(), key(hat.level(), "brim", hat.blockPosition(), body.getBbWidth()), () -> findBrimAshore(hat, body));
    }

    private static Vec3 findBrimAshore(PlacedHatEntity hat, YukkuriEntity body) {
        var edge = edges(body, hat.position(), 4, false).stream().findFirst().orElse(null);
        if (edge == null) return null;
        var toBank = hat.frame().toLocal(edge.edge());
        double bank = Math.atan2(toBank.z, toBank.x), growth = body.growthScale() * body.getScale() * HullBounds.SCALE / hat.hullScale();
        for (int i = 0; i < 24; i++) {
            var p = HullBounds.brim(growth, bank + (i % 2 == 1 ? -1 : 1) * ((i + 1) / 2) * Math.PI / 12);
            var local = new Vec3(p.x(), p.y(), p.z()).scale(1 / HullBounds.UNIT);
            if (!new Hop(hat, local).check(body).ok()) continue;
            if (ashore(body, hat.toWorld(local)) != null) return local;
        }
        return null;
    }

    /** A dry stand within a hop of {@code from} (the body's actual position, or a supported spot it can hop to first). */
    static Vec3 ashore(YukkuriEntity body, Vec3 from) {
        return kept(body.level(), key(body.level(), "ashore", Math.round(from.x * 4), Math.round(from.y * 4), Math.round(from.z * 4), body.getBbWidth()),
                () -> findAshore(body, from));
    }

    private static Vec3 findAshore(YukkuriEntity body, Vec3 from) {
        int asked = 0;
        for (var e : edges(body, from, 2, false)) {
            var at = e.stand(body.getBbWidth());
            if (hatOver(body, at, 0)) continue;
            if (asked++ >= ASKED) break;
            if (Hop.reaches(body, from, at)) return at;
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
        var search = new AABB(body.blockPosition()).inflate(range);
        // Observe one extra search-width outside the pickup box, without admitting those sticks as candidates.
        var sticks = body.level().getEntitiesOfClass(ItemEntity.class, search.inflate(range), item -> {
            if (!item.isAlive() || !item.getItem().is(Items.STICK)) return false;
            String reason = !search.intersects(item.getBoundingBox()) ? "out_of_range" : item.isInWater() ? "wet" : null;
            if (reason == null) return true;
            rejectStick(body, item, reason, null, range, walk);
            return false;
        });
        sticks.sort(Comparator.comparingDouble(body::distanceToSqr));
        for (int i = 0; i < sticks.size() && i < 3; i++) {
            var stick = sticks.get(i);
            var at = SurfaceApproach.floor(body, stick.position());
            if (at != null && (!walk || SurfaceApproach.canApproach(body, at))) return stick;
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
        var level = hat.level();
        var from = cell(hat);
        UUID id = kept(level, key(level, "food", body.getUUID(), from == null ? hat.blockPosition() : from, range), () -> {
            var found = findFood(hat, body, range);
            return found == null ? null : found.getUUID();
        });
        return id != null && level instanceof ServerLevel server && server.getEntity(id) instanceof ItemEntity item && item.isAlive()
                && !item.getItem().isEmpty() ? item : null;
    }

    private static ItemEntity findFood(PlacedHatEntity hat, YukkuriEntity body, int range) {
        var level = hat.level();
        var from = cell(hat);
        var items = level.getEntitiesOfClass(ItemEntity.class, hat.getBoundingBox().inflate(range, 3, range),
                item -> item.isAlive() && !item.hasPickUpDelay() && item.getItem().is(YukkuriEntity.FOOD) && body.life().acceptsFood(item.getItem()));
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

    /** A calm-water leg toward a bank berth whose planned captain seat reaches this ashore child, or null when no such
     *  water route exists. The caller waits rather than driving the hull onto land. */
    static Vec3 fetchTarget(PlacedHatEntity hat, YukkuriEntity body, YukkuriEntity child) {
        for (var edge : edges(body, child.position(), 4, false)) {
            if (!berthBraidReach(body, edge, List.of(child))) continue;
            var route = route(hat, edge.water());
            if (!route.isEmpty()) return leg(route);
        }
        return null;
    }

    /** The hull's collision box clears the bank while its brim reaches over the edge. */
    static Vec3 hatAt(Edge edge, YukkuriEntity owner) {
        return edge.onWater(ModEntities.PLACED_HAT.get().getWidth() * HatScale.of(owner) / 2 + .05);
    }

    static Vec3 boardingStand(Edge edge, PlacedHatEntity hat, YukkuriEntity body) {
        return edge.edge().subtract(edge.normal().scale(SurfaceApproach.supportRadius(body)));
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
