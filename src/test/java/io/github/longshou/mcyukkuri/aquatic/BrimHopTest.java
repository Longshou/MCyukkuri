package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HatSeats;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.HopPath;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrimHopTest {
    @Test void clearBankBoardsAndLeavesThroughTheBrimWithinUnchangedHopAbility() {
        for (double size : List.of(1.0, .3)) {
            var body = new HatSeats.Body("marisa", size, .8 * size, .6 * size);
            var seat = HatSeats.admit(1, body, size == 1, List.of());
            double edge = -HullBounds.RADIUS - .05;
            var bank = new Vec(edge - body.width() * .42, 1.0 / 9 + HullBounds.WATERLINE, 0);
            var ability = AbilityMath.compute(new AbilityMath.Inputs(size, 100, 20, 20, 0, false, true, false, false, 0),
                    AbilityMath.Coefficients.DEFAULTS, AbilityMath.Multipliers.IDENTITY);
            assertTrue(seat.sub(bank).hlen() > ability.hopReach());
            for (var ends : List.of(List.of(bank, seat), List.of(seat, bank))) {
                var path = HopPath.solve(ends.getFirst(), ends.getLast(), HullBounds.brimStops(size), (a, b) -> ArcSolver.lowest(a, b,
                        ability.hopHeight(), ability.hopReach(), ability.size(), points -> clear(a, points)
                                && points.stream().map(a::add).noneMatch(p -> p.x() - body.width() / 2 < edge && p.y() < bank.y() - .002)));
                assertNotNull(path, "size=" + size + " from=" + ends.getFirst());
                assertTrue(path.size() > 1);
            }
        }
    }

    @Test void hideUsesOneShortArcOnTheBraidSide() {
        var child = new HatSeats.Body("marisa", .3, .24, .18);
        var parent = new HatSeats.Body("marisa", 1, .8, .6);
        var captain = HatSeats.admit(1, parent, true, List.of());
        var from = HatSeats.admit(1, child, false, List.of(new HatSeats.Occupant(parent, captain, true)));
        var to = HullBounds.brim(.3, Math.toRadians(300));
        assertTrue(to.x() > 0 && to.z() < 0);
        var shape = BodyFootprint.of("marisa", child.width(), child.height(), 0);
        var adult = BodyFootprint.of("marisa", parent.width(), parent.height(), 0);
        var ability = AbilityMath.compute(new AbilityMath.Inputs(.3, 100, 20, 20, 0, false, true, false, false, 0),
                AbilityMath.Coefficients.DEFAULTS, AbilityMath.Multipliers.IDENTITY);
        for (var ends : List.of(List.of(from, to), List.of(to, from))) {
            var start = ends.getFirst();
            assertInstanceOf(ArcSolver.Plan.class, ArcSolver.lowest(start, ends.getLast(),
                    ability.hopHeight(), ability.hopReach(), ability.size(), points -> clear(start, points)
                            && points.stream().map(start::add).noneMatch(p -> shape.overlaps(p.x(), p.y(), p.z(), adult,
                                    captain.x(), captain.y(), captain.z(), 0))));
        }
    }

    @Test void smallJuvenileReachesBraidSideWithSeveralSupportedHops() {
        var b = new HatSeats.Body("marisa", .3, .24, .18);
        var from = HatSeats.admit(1, b, false, List.of());
        var to = HullBounds.brim(.3, 0);
        var stops = IntStream.range(0, 24).mapToObj(i -> HullBounds.brim(.3, i * Math.PI / 12)).toList();
        var ability = AbilityMath.compute(new AbilityMath.Inputs(.3, 100, 20, 20, 0, false, true, false, false, 0),
                AbilityMath.Coefficients.DEFAULTS, AbilityMath.Multipliers.IDENTITY);
        assertTrue(to.sub(from).hlen() > ability.hopReach());
        var path = HopPath.solve(from, to, stops, (a, c) -> ArcSolver.lowest(a, c,
                ability.hopHeight(), ability.hopReach(), ability.size(), points -> clear(a, points)));
        assertNotNull(path);
        for (var leg : path) {
            assertTrue(leg.at().sub(from).hlen() <= ability.hopReach());
            from = leg.at();
        }
        assertEquals(to, from);
        assertTrue(path.size() > 1);
    }

    @Test void blockedIntermediatePointsDoNotCreateAPath() {
        assertNull(HopPath.solve(Vec.ZERO, new Vec(2, 0, 0), List.of(new Vec(1, 0, 0)),
                (a, b) -> new ArcSolver.Blocked(ArcSolver.Kind.TOO_HIGH, 1)));
    }

    @Test void routeSearchCapsTheNumberOfStops() {
        var stops = IntStream.range(1, 1001).mapToObj(i -> new Vec(i, 0, 0)).toList();
        var calls = new int[1];
        var target = new Vec(2000, 0, 0);
        assertNull(HopPath.solve(Vec.ZERO, target, stops, (a, b) -> {
            calls[0]++;
            return b.equals(target) ? new ArcSolver.Blocked(ArcSolver.Kind.TOO_FAR, 1)
                    : new ArcSolver.Plan(1, 1, new Vec(1, 0, 0), 1, 1);
        }));
        assertTrue(calls[0] <= (HopPath.MAX_STOPS + 1) * (HopPath.MAX_STOPS + 1));
    }

    private static boolean clear(Vec from, List<Vec> points) {
        return points.stream().map(from::add).allMatch(p -> !Double.isFinite(HullBounds.surface(p.hlen()))
                || p.y() >= HullBounds.surface(p.hlen()) - .05);
    }
}
