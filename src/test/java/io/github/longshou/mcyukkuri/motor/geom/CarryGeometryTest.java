package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import java.util.ArrayList;
import java.util.List;
import io.github.longshou.mcyukkuri.motor.geom.CarryGeometry.Rejection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CarryGeometryTest {
    @Test void childOffHullFootprintStillLiftsAboveWater() {
        double hull = 64 + HullBounds.surface(HullBounds.RADIUS + .2);
        assertTrue(Double.isNaN(hull), "a child outside the hull has no surface beneath it");
        assertEquals(64.3, CarryGeometry.liftHeight(63.8, 64, hull, .3), 1e-9);
        assertEquals(64.6, CarryGeometry.liftHeight(63.8, 64, 64.3, .3), 1e-9);
        assertEquals(65, CarryGeometry.liftHeight(65, 64, hull, .3), 1e-9);
    }

    @Test void occupiedLoweringEndpointRedirectsWithoutJumpingOrAccelerating() {
        var start = new Vec(0, 1, 0);
        var first = new Vec(.2, 0, 0);
        double pace = .05;
        var current = CarryGeometry.lowering(start, first, pace, 9);
        var replacement = new Vec(-.4, .1, .5);
        assertEquals(current, CarryGeometry.lowering(current, replacement, pace, 0));
        var previous = current;
        for (int tick = 1; tick <= 40; tick++) {
            var next = CarryGeometry.lowering(current, replacement, pace, tick);
            assertTrue(CarryGeometry.releaseRemaining(previous, next, next.y()) <= pace + 1e-9);
            previous = next;
        }
        assertEquals(replacement, previous);
        assertEquals(replacement, CarryGeometry.lowering(replacement, replacement, pace, 0));
        int duration = CarryGeometry.loweringTicks(1, pace);
        assertTrue(CarryGeometry.lowering(Vec.ZERO, new Vec(0, 1, 0), pace, 1).y() < pace / 2);
        assertTrue(1 - CarryGeometry.lowering(Vec.ZERO, new Vec(0, 1, 0), pace, duration - 1).y() < pace / 2);
    }

    @Test void brimReleaseGoesAroundTheHeadBeforeDescendingAtTheCushion() {
        var from = new Vec(-.8, .8, 0);
        var to = new Vec(.9, .1, 0);
        for (int i = 0; i <= 100; i++) {
            var p = CarryGeometry.brimRelease(from, to, .2, 1, i / 100.0);
            assertTrue(p.hlen() >= .8 - 1e-9, "opposite-side cushion must not cut through the head");
            if (p.y() < .8) {
                assertEquals(to.x(), p.x(), 1e-9);
                assertEquals(to.z(), p.z(), 1e-9);
            }
        }
        assertEquals(from, CarryGeometry.brimRelease(from, to, .2, 1, 0));
        assertEquals(to.x(), CarryGeometry.brimRelease(from, to, .2, 1, 1).x(), 1e-9);
        assertEquals(to.y(), CarryGeometry.brimRelease(from, to, .2, 1, 1).y(), 1e-9);
    }
    @Test void braidReachUsesTheNearestBodyPointAndTheActualChainLength() {
        var root = new Vec(0, 1, 0);
        var low = new Vec(1, 0, -.1); var high = new Vec(1.2, .3, .1);
        assertEquals(Math.hypot(1, .7) - 1.3, CarryGeometry.reachGap(root, low, high, 1.3), 1e-9);
        assertTrue(CarryGeometry.reachGap(root, low, high, 1.3) < 0);
        assertTrue(CarryGeometry.reachGap(root, low, high, 1.2) > 0);
    }

    @Test void brimDestinationNeverBypassesObstruction() {
        var seat = new Vec(.2, -.3, -.4); var from = new Vec(2, 0, -2);
        assertEquals(seat, CarryGeometry.wayOut(0, 0, from, seat, p -> null, (p, a) -> true).point());
        assertNull(CarryGeometry.wayOut(0, 0, from, seat, p -> Rejection.TERRAIN, (p, a) -> fail()).point());
        assertNull(CarryGeometry.wayOut(0, 0, from, seat, p -> null, (p, a) -> false).point());
    }

    @Test void releaseConvergesAtPinnedAndUnpinnedFeetWithoutAcceptingHorizontalError() {
        var endpoint = new Vec(1, 0, 2);
        for (double support : new double[]{0, .04}) {
            double previous = Double.POSITIVE_INFINITY;
            for (int tick = 0; tick <= 10; tick++) {
                var feet = new Vec(1, support + (1 - tick / 10.0), 2);
                double remaining = CarryGeometry.releaseRemaining(feet, endpoint, support);
                assertTrue(remaining < previous);
                previous = remaining;
            }
            assertEquals(0, previous);
            assertEquals(.1, CarryGeometry.releaseRemaining(new Vec(1.1, support, 2), endpoint, support), 1e-9);
        }
        assertEquals(.04, CarryGeometry.releaseRemaining(new Vec(1, .04, 2), endpoint, 0), 1e-9,
                "the old unpinned endpoint never reached the .02 release tolerance");
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
