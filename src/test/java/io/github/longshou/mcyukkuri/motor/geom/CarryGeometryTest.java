package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CarryGeometryTest {
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
