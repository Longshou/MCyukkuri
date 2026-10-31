package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.List;
import io.github.longshou.mcyukkuri.motor.geom.CarryGeometry.Rejection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CarryGeometryTest {
    @Test void blockedFrontDoesNotEndTheTurnSearchAtAClearRearSpot() {
        // A hat occupies the forward braid workspace beside a parent; the rear is clear.
        assertTrue(clearLanding(new Vec(7.9297866, 0, 1.1988144), 0));
        var blocked = CarryGeometry.wayOut(6.9333333, -2.52, 3.85, new Vec(8.5098, .21227, -4.208), new Vec(8, 2, -8), 8,
                p -> clearLanding(p, 0) ? null : Rejection.HOST, (p, arc) -> fail("no landing clears the hat"));
        assertNull(blocked.point(), "a clear rear spot must not satisfy wayOut");
        assertTrue(blocked.rejected()[Rejection.CARRIER.ordinal()] > 0);
        assertTrue(blocked.rejected()[Rejection.HOST.ordinal()] > 0);
        var turned = CarryGeometry.wayOut(6.9333333, -2.52, 3.85, new Vec(8.5098, .21227, -4.208), new Vec(8, 2, -8), 8,
                p -> clearLanding(p, 15) ? null : Rejection.HOST, (p, arc) -> true);
        assertNotNull(turned.point(), "three turn steps free a real front spot");
        assertTrue(clearLanding(turned.point(), 15));
    }

    @Test void wayOutCountsRejectionsAndChecksTheRouteOnlyAfterLandingClearance() {
        var tip = new Vec(.2, 0, -2);
        var points = CarryGeometry.endpoints(0, -2, 3, tip);
        var landings = new ArrayList<Vec>();
        var arcs = new ArrayList<Double>();
        var way = CarryGeometry.wayOut(0, -2, 3, tip, new Vec(1, 0, 1), 0, p -> {
            landings.add(p);
            return switch (landings.size()) {
                case 1 -> Rejection.HOST;
                case 2 -> Rejection.TERRAIN;
                case 3 -> Rejection.OCCUPIED;
                case 4 -> Rejection.UNSUPPORTED;
                default -> null;
            };
        }, (p, arc) -> {
            assertTrue(landings.size() >= 5);
            arcs.add(arc);
            return !p.equals(points.get(4));
        });
        assertEquals(points.get(5), way.point());
        assertEquals(points.subList(0, 6), landings);
        assertArrayEquals(new int[]{0, 1, 1, 1, 1, 1}, way.rejected());
        assertEquals(3, arcs.size(), "two blocked arcs, then the first clear arc of the next landing");
    }

    @Test void endpointsKeepReachableFrontTipRankFacingFirstAndExcludeSideMargin() {
        var tip = new Vec(.2, .1, -2);
        var seats = CarryGeometry.endpoints(0, -2, 3, tip);
        assertTrue(seats.contains(tip));
        assertTrue(Math.abs(Math.toDegrees(Math.atan2(seats.getFirst().x(), -seats.getFirst().z()))) < 10);
        for (var excluded : List.of(new Vec(2, 0, 0), new Vec(2, 0, -.1), new Vec(0, 0, -5)))
            assertFalse(CarryGeometry.endpoints(0, 0, 4, excluded).contains(excluded));
        var visible = new Vec(2, 0, -.5);
        assertTrue(CarryGeometry.endpoints(0, 0, 4, visible).contains(visible));
    }

    private static boolean clearLanding(Vec at, double turn) {
        double angle = Math.toRadians(turn), x = at.x() * Math.cos(angle) - at.z() * Math.sin(angle);
        double z = at.x() * Math.sin(angle) + at.z() * Math.cos(angle);
        return Math.hypot(x, z) >= 8 && Math.hypot(x - 8, z + 8) >= 8.5;
    }

    @Test void endpointsPreferFacingBeforeDistance() {
        var front = new Vec(0, 0, -10);
        var side = new Vec(2, 0, 0);
        var rear = new Vec(0, 0, 1);
        var points = List.of(rear, side, front).stream().sorted((a, b) -> CarryGeometry.compareEndpoints(a.x(), a.z(), b.x(), b.z())).toList();
        assertEquals(List.of(front, side, rear), points);
    }

    @Test void equalBearingsPreferNearerEndpointsAndTreatBothSidesEqually() {
        assertTrue(CarryGeometry.compareEndpoints(1, -1, 2, -2) < 0);
        assertEquals(0, CarryGeometry.compareEndpoints(-1, -1, 1, -1));
    }

    @Test void nearbyBearingsUseDistanceWithinTheFacingBand() {
        assertTrue(CarryGeometry.compareEndpoints(.15, -1, .1, -2) < 0);
        assertTrue(CarryGeometry.compareEndpoints(.3, -1, .1, -2) > 0);
    }

    @Test void sameBearingNeverSweepsAFullCircle() {
        for (double z : new double[]{-1, 1}) assertEquals(0, CarryGeometry.frontTurn(1, z, 2, z * 2));
        var tried = new ArrayList<Double>();
        assertFalse(CarryGeometry.chooseArc(0, turn -> { tried.add(turn); return false; }));
        assertEquals(List.of(0.0), tried);
    }

    @Test void blockedFrontSweepUsesTheClearRearRouteWithTheSameEndpoints() {
        double front = CarryGeometry.frontTurn(1, 1, -1, 1);
        var tried = new ArrayList<Double>();
        assertTrue(CarryGeometry.chooseArc(front, turn -> {
            tried.add(turn);
            for (int i = 0; i <= 20; i++) {
                var point = CarryGeometry.swing(0, 0, 1, 1, -1, 1, turn, i / 20.0);
                if (point.z() < -1) return false; // A wall across the front route.
            }
            var end = CarryGeometry.swing(0, 0, 1, 1, -1, 1, turn, 1);
            assertEquals(-1, end.x(), 1e-9); assertEquals(1, end.z(), 1e-9);
            return true;
        }));
        assertEquals(List.of(front, CarryGeometry.otherTurn(front)), tried);
        tried.clear();
        assertTrue(CarryGeometry.chooseArc(front, turn -> { tried.add(turn); return true; }));
        assertEquals(List.of(front), tried, "a clear front route must not probe the rear");
        assertFalse(CarryGeometry.chooseArc(front, turn -> false));
    }

    @Test void liveMouthBlendIsContinuousAtLiftEndAndLoweringStart() {
        assertEquals(0, CarryGeometry.mouthBlend(0, 0));
        assertEquals(1, CarryGeometry.mouthBlend(1, 0));
        assertEquals(0, CarryGeometry.mouthBlend(1, 1));
        // With a 0.25-block mismatch between static seat and live lip, neither handoff may snap.
        double lipDelta = .25;
        assertEquals(lipDelta, lipDelta * CarryGeometry.mouthBlend(1 - 1e-6, 0), 1e-9);
        assertEquals(lipDelta, lipDelta * CarryGeometry.mouthBlend(1, 1e-6), 1e-9);
    }

    @Test void sweepTakesTheFrontEvenWhenTheRearArcIsShorter() {
        double turn = CarryGeometry.frontTurn(1, 1, -1, 1);
        assertTrue(Math.abs(turn) > Math.PI);
        assertTrue(CarryGeometry.swing(0, 0, 1, 1, -1, 1, turn, .5).z() < 0);
        assertTrue(CarryGeometry.swing(0, 0, 1, 1, -1, 1, CarryGeometry.otherTurn(turn), .5).z() > 0);
    }

    @Test void bothArcsKeepThePickupAndSetDown() {
        double front = CarryGeometry.frontTurn(2, -3, -4, -1);
        for (double turn : new double[]{front, CarryGeometry.otherTurn(front)}) {
            var start = CarryGeometry.swing(7, -2, 9, -5, 3, -3, turn, 0);
            var end = CarryGeometry.swing(7, -2, 9, -5, 3, -3, turn, 1);
            assertEquals(9, start.x(), 1e-9); assertEquals(-5, start.z(), 1e-9);
            assertEquals(3, end.x(), 1e-9); assertEquals(-3, end.z(), 1e-9);
        }
    }

    @Test void mouthLiftClearsTheHostBeforeCrossingAndArrivesAtTheLips() {
        var from = new Vec(0, 1, 0);
        var to = new Vec(2, .5, 0);
        double length = CarryGeometry.liftLength(from, to, 2);
        assertEquals(from, CarryGeometry.liftPoint(from, to, 2, 0));
        assertEquals(new Vec(0, 2, 0), CarryGeometry.liftPoint(from, to, 2, 1 / length));
        assertEquals(new Vec(1, 2, 0), CarryGeometry.liftPoint(from, to, 2, 2 / length));
        assertEquals(to, CarryGeometry.liftPoint(from, to, 2, 1));
    }

    @Test void mouthMotionDurationFollowsDistanceAndAbility() {
        assertEquals(10, CarryGeometry.motionTicks(.5, .05));
        assertEquals(20, CarryGeometry.motionTicks(1, .05));
        assertEquals(40, CarryGeometry.motionTicks(1, .025));
        var from = new Vec(0, 0, 0);
        var to = new Vec(0, .5, 0);
        assertEquals(to, CarryGeometry.liftPoint(from, to, .5, 1));
    }
}
