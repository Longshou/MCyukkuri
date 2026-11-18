package io.github.longshou.mcyukkuri.motor.geom;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HopHeadingTest {
    @Test void verticalBrimHopKeepsFacingThroughSupportHeightChanges() {
        var from = new Vec(.93, -.07, .164);
        for (double height : new double[]{-.05, 0, .12}) {
            assertEquals(-90, HopHeading.yaw(from, new Vec(.93, height, .164), -90, .05));
            assertEquals(-90, HopHeading.yaw(from, new Vec(.93001, height, .16399), -90, .05));
        }
    }

    @Test void horizontalHopStillFacesItsDestination() {
        assertEquals(90, HopHeading.yaw(Vec.ZERO, new Vec(-1, .2, 0), 0, .05));
        assertEquals(0, HopHeading.yaw(Vec.ZERO, new Vec(0, .2, 1), -90, .05));
    }

    @Test void turnAcrossNorthAndThenReverseOnTheNextLeg() {
        double yaw = 170;
        for (double bearing : new double[]{-165, 173, -100, 80}) {
            double error = Effort.turn(yaw, bearing, 180);
            boolean stood = false;
            while (HopHeading.standingTurn(error)) {
                yaw += Effort.turn(yaw, bearing, 12);
                error = Effort.turn(yaw, bearing, 180);
                stood = true;
            }
            assertEquals(bearing == 80, stood);
            for (int remaining = 6; remaining > 0; remaining--) {
                double step = HopHeading.crouchTurn(error, remaining, 7, 12);
                assertTrue(Math.abs(step) <= 12);
                yaw += step;
                error = Effort.turn(yaw, bearing, 180);
            }
            assertTrue(Math.abs(error) > 0 && Math.abs(error) <= 45);
            double start = yaw;
            for (int segment = 1; segment <= 7; segment++) {
                double next = HopHeading.flightYaw(start, bearing, segment, 7);
                assertTrue(Math.abs(next - yaw) <= 12);
                yaw = next;
            }
            assertEquals(0, Effort.turn(yaw, bearing, 180), 1e-6);
        }
    }

    @Test void takeoffReservesEnoughFlightToFaceTheLanding() {
        assertFalse(HopHeading.standingTurn(-89.99));
        assertTrue(HopHeading.standingTurn(90));
        assertTrue(HopHeading.standingTurn(-90));
        assertEquals(45, HopHeading.launchAllowance(9, 10));
        assertEquals(20, HopHeading.launchAllowance(3, 10));
        assertEquals(0, HopHeading.launchAllowance(1, 10));
        assertEquals(-10, HopHeading.crouchTurn(-89, 1, 3, 10));
        assertEquals(12, HopHeading.flightYaw(12, 12, 1, 1));
    }
}
