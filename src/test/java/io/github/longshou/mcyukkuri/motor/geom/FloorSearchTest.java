package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.HullProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FloorSearchTest {
    private static final Vec CENTRE = Vec.ZERO;
    private static final double RADIUS = 4, CONE = Math.acos(.97);

    private static List<Vec> route(Vec from, Vec to, BiPredicate<Double, Double> clear) {
        if (!clear.test(to.x(), to.z())) return List.of();
        var grid = new HeightGrid(-6, -6, 6, 6, .125,
                (x, z) -> clear.test(x, z) ? 0 : Double.NaN);
        var path = GridPath.find(grid, from.x(), from.z(), to.x(), to.z(), 0, 0);
        if (path.isEmpty()) return List.of();
        var out = new ArrayList<>(path.stream().map(p -> new Vec(p[0], 0, p[1])).toList());
        out.add(to);
        return out;
    }

    @Test void occupiedNearEdgeChoosesAFreeContactWithoutMovingTheCaregiver() {
        var from = new Vec(4, 0, -2);
        var sibling = new Vec(RADIUS * Math.cos(CONE), 0, -RADIUS * Math.sin(CONE));
        var path = FloorSearch.coneRoute(from, CENTRE, 0, RADIUS, RADIUS, CONE,
                to -> route(from, to, (x, z) -> Math.hypot(x - sibling.x(), z - sibling.z()) > .7
                        && Math.hypot(x, z) > 3));
        assertFalse(path.isEmpty());
        var target = path.getLast();
        assertEquals(RADIUS, target.hlen(), 1e-9);
        assertTrue(Math.abs(Math.atan2(target.z(), target.x())) <= CONE + 1e-9);
        assertTrue(target.sub(sibling).hlen() > .7);
        assertTrue(path.stream().allMatch(at -> at.hlen() > 3 && at.sub(sibling).hlen() > .7));
    }

    @Test void freeButDisconnectedNearEdgeDoesNotHideAReachableConePoint() {
        var from = new Vec(4, 0, -2);
        var path = FloorSearch.coneRoute(from, CENTRE, 0, RADIUS, RADIUS, CONE,
                to -> to.z() < 0 ? List.of() : List.of(new Vec(5, 0, -2), new Vec(5, 0, 1), to));
        assertEquals(3, path.size());
        assertTrue(path.getLast().z() >= 0);
    }

    @Test void routesAroundTheCaregiversBodyToTheOppositeSide() {
        var from = new Vec(-4, 0, 0);
        var path = FloorSearch.coneRoute(from, CENTRE, 0, RADIUS, RADIUS, CONE,
                to -> route(from, to, (x, z) -> Math.hypot(x, z) > 3));
        assertFalse(path.isEmpty());
        assertTrue(path.stream().anyMatch(at -> Math.abs(at.z()) > 3));
        assertTrue(path.stream().allMatch(at -> at.hlen() > 3));
        assertTrue(path.getLast().x() > 3);
    }

    @Test void noReachableContactReturnsNoRoute() {
        assertTrue(FloorSearch.coneRoute(new Vec(4, 0, -2), CENTRE, 0, RADIUS, RADIUS, CONE,
                to -> List.of()).isEmpty());
    }

    @Test void theFloorRouteGoesAroundACornerWhoseClearCellsCannotBeCrossedDirectly() {
        var grid = new HeightGrid(-3, -3, 3, 3, 1, (x, z) -> 0);
        var path = GridPath.find(grid, -1.5, -.5, 1.5, .5, 0, 0,
                (from, to) -> from.x() * to.x() >= 0 || Math.min(from.z(), to.z()) > 1);
        assertFalse(path.isEmpty());
        assertTrue(path.stream().anyMatch(at -> at[1] > 1));
        for (int i = 1; i < path.size(); i++) {
            var from = path.get(i - 1); var to = path.get(i);
            assertTrue(from[0] * to[0] >= 0 || Math.min(from[1], to[1]) > 1);
        }
    }

    @Test void coneAcrossTheAngleSeamKeepsTheNearestContact() {
        var from = new Vec(-5, 0, -.1);
        var path = FloorSearch.coneRoute(from, CENTRE, Math.PI, RADIUS, RADIUS, CONE, List::of);
        var target = path.getLast();
        assertEquals(RADIUS, target.hlen(), 1e-9);
        assertEquals(from.z() / from.x(), target.z() / target.x(), 1e-9);
    }

    @Test void aContactNearerTheCaregiverCanLeaveRoomToTurnBelowTheWall() {
        var path = FloorSearch.coneRoute(new Vec(5, 0, 0), CENTRE, 0, 3, 4, CONE,
                to -> to.hlen() <= 3.5 ? List.of(to) : List.of());
        assertFalse(path.isEmpty());
        assertTrue(path.getLast().hlen() >= 3 && path.getLast().hlen() <= 3.5);
    }

    @Test void newbornHasATurnableRearContactOnTheExportedHollow() {
        double unit = HullBounds.UNIT, width = .096 / unit, height = .072 / unit;
        var hull = HullProfile.of(HullProfile.BASIN);
        var parent = BodyFootprint.of("marisa", .8 / unit, .6 / unit, 0);
        var parentAt = new Vec(0, hull.support(0, 0, parent), 0);
        var child = BodyFootprint.of("marisa", width, height, Math.toRadians(-95));
        var sibling = BodyFootprint.of("marisa", .1 / unit, .075 / unit, 0);
        var path = FloorSearch.coneRoute(new Vec(0, hull.floor(), -4.22), parentAt, -Math.PI / 2,
                .398 / unit, .498 / unit, CONE, target -> {
                    double y = hull.support(target.x(), target.z(), child);
                    if (!Double.isFinite(y) || (y - hull.floor()) * unit > .02) return List.of();
                    for (int yaw = -95; yaw <= 0; yaw += 5) {
                        var body = BodyFootprint.of("marisa", width, height, Math.toRadians(yaw));
                        var envelope = BodyFootprint.envelope("marisa", width, height, Math.toRadians(yaw));
                        double next = hull.support(target.x(), target.z(), envelope);
                        if (!Double.isFinite(next) || (next - y) * unit > .02 || (next - hull.floor()) * unit > .02
                                || body.overlaps(target.x(), next, target.z(), parent, 0, parentAt.y(), 0, .02)
                                || body.overlaps(target.x(), next, target.z(), sibling, -1.05, hull.floor(), -4.82, .02)) return List.of();
                        y = next;
                    }
                    return List.of(new Vec(target.x(), y, target.z()));
                });
        assertFalse(path.isEmpty());
        assertTrue(Math.abs(path.getLast().y() - parentAt.y()) * unit <= .18);
    }

    @Test void anExistingHatOverlapMayOnlySeparateFromEveryObstacle() {
        var sibling = new Disc(0, 0, 1, 0, 1);
        var from = new Disc(1.5, 0, 1, 0, 1);
        assertFalse(PlaceGeometry.cabin(from, 10, List.of(), List.of(sibling), .02));
        assertTrue(PlaceGeometry.cabinStep(from, 10, new Disc(1.7, 0, 1, 0, 1), 10, List.of(), List.of(sibling), .02));
        assertFalse(PlaceGeometry.cabinStep(from, 10, from, 10, List.of(), List.of(sibling), .02));
        assertFalse(PlaceGeometry.cabinStep(from, 10, new Disc(1.3, 0, 1, 0, 1), 10, List.of(), List.of(sibling), .02));
        assertFalse(PlaceGeometry.cabinStep(new Disc(2.2, 0, 1, 0, 1), 10, from, 10, List.of(), List.of(sibling), .02));
        assertFalse(PlaceGeometry.cabinStep(from, 10, new Disc(1.7, 0, 1, 0, 1), 10,
                List.of(), List.of(sibling, new Disc(3.6, 0, 1, 0, 1)), .02));
    }

    @Test void theWholeContactConeLeavesRoomBesideAFinishedSibling() {
        var hull = HullProfile.of(HullProfile.BASIN);
        var parent = new FloorSearch.Placed(BodyFootprint.of("marisa", 9.1428581, 6.8571432, 0), new Vec(0, hull.floor(), 0));
        var sibling = new FloorSearch.Placed(BodyFootprint.of("marisa", 1.1162994, .8372245, Math.toRadians(355)),
                new Vec(.0239396, hull.floor(), -4.7386357));
        var body = BodyFootprint.of("marisa", 1.1624491, .8718368, Math.toRadians(120.47571));
        var from = new Vec(-1.3232023, hull.floor(), -4.9008399);
        var hair = List.of(new FloorSearch.Placed(BodyFootprint.envelope("marisa", parent.body().width(), parent.body().height(), 0), parent.at()));
        var siblingHat = wornHat(sibling.body(), sibling.at());
        var path = FloorSearch.coneRoute(from, parent.at(), -Math.PI / 2, .3508572 / HullBounds.UNIT,
                .5508572 / HullBounds.UNIT, CONE, at -> {
                    double y = hull.support(at.x(), at.z(), body);
                    if (!Double.isFinite(y) || (y - hull.floor()) * HullBounds.UNIT > .02
                            || !FloorSearch.clear(body, new Vec(at.x(), y, at.z()), List.of(parent, sibling), .02)) return List.of();
                    var hat = wornHat(body, new Vec(at.x(), y, at.z()));
                    if (!PlaceGeometry.cabin(hat, HullBounds.innerRadius(hat.bottom() * HullBounds.UNIT) / HullBounds.UNIT,
                            hair, List.of(siblingHat), .3 / HullBounds.SCALE)) return List.of();
                    double targetYaw = Math.toDegrees(Math.atan2(at.x(), -at.z()));
                    for (double yaw = 120.47571; yaw >= targetYaw; yaw -= 5) {
                        var turned = BodyFootprint.of("marisa", body.width(), body.height(), Math.toRadians(yaw));
                        var envelope = BodyFootprint.envelope("marisa", body.width(), body.height(), Math.toRadians(yaw));
                        double next = hull.support(at.x(), at.z(), envelope);
                        if (!Double.isFinite(next) || (next - y) * HullBounds.UNIT > .02
                                || (next - hull.floor()) * HullBounds.UNIT > .02
                                || !FloorSearch.clear(turned, new Vec(at.x(), next, at.z()), List.of(parent, sibling), .02)) return List.of();
                        y = next;
                    }
                    return List.of(new Vec(at.x(), y, at.z()));
                });
        assertFalse(path.isEmpty());
        assertTrue(path.getLast().sub(sibling.at()).hlen() > 1);
    }

    private static Disc wornHat(BodyFootprint body, Vec at) {
        double size = body.width() * HullBounds.UNIT / .8;
        double bottom = at.y() + GeometryJson.read("yukkuri_presentation/hat_vessel.json").field("anchor_worn").getAsJsonArray().get(1).getAsDouble() * size / 16 / HullBounds.UNIT;
        double radius = Math.max(body.width() * .5 + .02 / HullBounds.UNIT,
                HullProfile.of(HullProfile.BASIN).wornBrim()[0] * HullBounds.SCALE * size / 16 / HullBounds.UNIT);
        return new Disc(at.x(), at.z(), radius, bottom, bottom + .3 * size / 16 / HullBounds.UNIT);
    }

    @Test void aFreeContactAcrossAClosedFloorRemainsUnreachable() {
        var hull = HullProfile.of(HullProfile.BASIN);
        var parent = new FloorSearch.Placed(BodyFootprint.of("marisa", 9.1428581, 6.8571432, Math.toRadians(20)), new Vec(0, hull.floor(), 0));
        var sibling = new FloorSearch.Placed(BodyFootprint.of("marisa", 1.1162994, .8372245, Math.toRadians(330)), new Vec(.2624404, hull.floor(), -4.8062839));
        var body = BodyFootprint.of("marisa", 1.1526531, .8644898, Math.toRadians(160.2987));
        var from = new Vec(-1.0733122, hull.floor(), -4.7582234);
        var hair = List.of(new FloorSearch.Placed(BodyFootprint.envelope("marisa", parent.body().width(), parent.body().height(), parent.body().yaw()), parent.at()));
        var siblingHat = wornHat(sibling.body(), sibling.at());
        var obstacles = List.of(parent, sibling);
        var startHat = wornHat(body, from);
        double wall = HullBounds.innerRadius(startHat.bottom() * HullBounds.UNIT) / HullBounds.UNIT;
        HeightGrid.Sampler support = (x, z) -> {
            double y = hull.support(x, z, body);
            if (!Double.isFinite(y) || (y - hull.floor()) * HullBounds.UNIT > .02) return Double.NaN;
            var at = new Vec(x, y, z); var hat = wornHat(body, at);
            return FloorSearch.clear(body, at, obstacles, .02)
                    && PlaceGeometry.cabinStep(startHat, wall, hat, HullBounds.innerRadius(hat.bottom() * HullBounds.UNIT) / HullBounds.UNIT,
                    hair, List.of(siblingHat), .3 / HullBounds.SCALE) ? y : Double.NaN;
        };
        var grid = new HeightGrid(-7, -7, 7, 7, FloorSearch.GRID_CELL, support);
        var contacts = new ArrayList<Vec>();
        var path = FloorSearch.coneRoute(from, parent.at(), Math.toRadians(-70.43454), 4.0048984, 6.2906127, CONE, at -> {
            double y = support.height(at.x(), at.z());
            if (!Double.isFinite(y)) return List.of();
            if (!PlaceGeometry.cabin(wornHat(body, new Vec(at.x(), y, at.z())), wall, hair, List.of(siblingHat), .3 / HullBounds.SCALE)) return List.of();
            double targetYaw = Math.toDegrees(Math.atan2(at.x(), -at.z()));
            for (double yaw = 160.2987; yaw >= targetYaw; yaw -= 5) {
                var turned = BodyFootprint.of("marisa", body.width(), body.height(), Math.toRadians(yaw));
                var envelope = BodyFootprint.envelope("marisa", body.width(), body.height(), Math.toRadians(yaw));
                double next = hull.support(at.x(), at.z(), envelope);
                if (!Double.isFinite(next) || (next - y) * HullBounds.UNIT > .02
                        || (next - hull.floor()) * HullBounds.UNIT > .02
                        || !FloorSearch.clear(turned, new Vec(at.x(), next, at.z()), obstacles, .02)) return List.of();
                y = next;
            }
            contacts.add(at);
            var start = grid.nearest(from.x(), from.z()); var end = grid.nearest(at.x(), at.z());
            if (start == null || end == null) return List.of();
            return GridPath.find(grid, grid.centreX(start[0]), grid.centreZ(start[1]), grid.centreX(end[0]), grid.centreZ(end[1]), .02 / HullBounds.UNIT, 0,
                    (a, b) -> Double.isFinite(support.height((a.x() + b.x()) / 2, (a.z() + b.z()) / 2))
                            && PlaceGeometry.cabinStep(wornHat(body, a), wall, wornHat(body, b), wall, hair, List.of(siblingHat), .3 / HullBounds.SCALE))
                    .stream().map(p -> new Vec(p[0], hull.floor(), p[1])).toList();
        });
        assertFalse(contacts.isEmpty());
        assertTrue(path.isEmpty());
    }

    @Test void newbornCanStepOutOfTheRealPostBirthHairOverlap() {
        double unit = HullBounds.UNIT;
        var hull = HullProfile.of(HullProfile.BASIN);
        var parent = new FloorSearch.Placed(BodyFootprint.envelope("marisa", .8 / unit, .6 / unit, 0), new Vec(0, hull.floor(), 0));
        double radius = hull.wornBrim()[0] * HullBounds.SCALE * .12 / 16 / unit;
        double bottom = hull.floor() + GeometryJson.read("yukkuri_presentation/hat_vessel.json").field("anchor_worn").getAsJsonArray().get(1).getAsDouble() * .12 / 16 / unit;
        var from = new Disc(0, -4.2170515, radius, bottom, bottom + .3 * .12 / 16 / unit);
        var to = new Disc(.12, -4.38, radius, from.bottom(), from.top());
        double wall = HullBounds.innerRadius(bottom * unit) / unit, gap = .3 / HullBounds.SCALE;
        assertFalse(PlaceGeometry.cabin(from, wall, List.of(parent), List.of(), gap));
        assertTrue(PlaceGeometry.cabinStep(from, wall, to, wall, List.of(parent), List.of(), gap));
        assertFalse(PlaceGeometry.cabinStep(to, wall, from, wall, List.of(parent), List.of(), gap));
    }
}
