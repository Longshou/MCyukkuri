package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
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

    @Test void transferClearsTheCarrierAndDescendsOnlyAboveItsDestination() {
        var from = new Vec(10, 0, -1);
        var root = new Vec(6.93, 9.33, -2.52);
        for (var to : List.of(new Vec(2, 0, -8), new Vec(0, 10.6, 0))) {
            double height = to.y() + 4;
            Vec previous = from;
            for (int i = 0; i <= 1000; i++) {
                double tick = i * 50.0 / 1000;
                var p = CarryGeometry.transfer(from, to, root, CarryGeometry.BODY_LOW, CarryGeometry.BODY_HIGH,
                        2, height, tick, 19, 34, 50);
                assertFalse(p.y() < CarryGeometry.BODY_HIGH.y() && p.x() > CarryGeometry.BODY_LOW.x() - 2 + 1e-9
                        && p.x() < CarryGeometry.BODY_HIGH.x() + 2 - 1e-9 && p.z() > CarryGeometry.BODY_LOW.z() - 2 + 1e-9
                        && p.z() < CarryGeometry.BODY_HIGH.z() + 2 - 1e-9, "body intersection at " + p);
                assertTrue(Math.hypot(p.sub(previous).hlen(), p.y() - previous.y()) < .2, "continuous path at " + p);
                if (tick >= 34) { assertEquals(to.x(), p.x(), 1e-9); assertEquals(to.z(), p.z(), 1e-9); }
                previous = p;
            }
            assertEquals(to, previous);
        }
    }
    @Test void raisedAndSideHeldBodiesClearTheCarrierWhileCrossingItsFaceDoesNot() {
        assertTrue(CarryGeometry.clearsBody(new Vec(11.7, 3.7, -4.2), 2.24, 3.36));
        assertTrue(CarryGeometry.clearsBody(new Vec(2, 10.6, 0), 2.24, 3.36));
        assertFalse(CarryGeometry.clearsBody(new Vec(5, 3.7, -4.2), 2.24, 3.36));
        assertTrue(CarryGeometry.clearsBody(new Vec(2, 1, -8), 2.24, 3.36));
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
    }

    @Test void blockedFrontSweepCannotCarryBehindTheBody() {
        var result = CarryGeometry.wayOut(0, 0, new Vec(1, 0, 1), new Vec(-1, 0, 1), p -> null, (p, turn) -> {
            for (int i = 0; i <= 20; i++) {
                var point = CarryGeometry.swing(0, 0, 1, 1, -1, 1, turn, i / 20.0);
                if (point.z() < -1) return false; // A wall across the front route.
            }
            return true;
        });
        assertNull(result.point());
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
