package io.github.longshou.mcyukkuri.human;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeatPoseTest {
    private static final double[] FRONT = {10.5, 64, 20.5}, TOP = {10.5, 64.5, 19.5};

    private static double[] drawn(boolean sitting, double s, double[] entity, double[] anchor) {
        var off = SeatPose.offset(sitting, s, entity, anchor);
        return new double[]{entity[0] + off[0], entity[1] + off[1], entity[2] + off[2]};
    }

    @Test void easeStartsAndEndsSlowlyAndNeverLeavesTheRange() {
        assertEquals(0, SeatPose.ease(-1), 1e-9);
        assertEquals(1, SeatPose.ease(2), 1e-9);
        double last = 0;
        for (int i = 0; i <= 20; i++) {
            double e = SeatPose.ease(i / 20.0);
            assertTrue(e >= last, "monotone at " + i);
            last = e;
        }
        assertTrue(SeatPose.ease(.1) < .1 && SeatPose.ease(.9) > .9);
    }

    @Test void seatedHipsAreOnTheBoardAndTheThighsNotThroughIt() {
        assertEquals(.65, SeatPose.hipY(SeatPose.BOARD), 1e-9);
        double gap = SeatPose.thighGap(SeatPose.BOARD);
        assertTrue(gap >= 0 && gap < .05, "thigh underside sits on the board, not in it: " + gap);
        // A bench piece's board ends 7/16 from its centre; the drawn feet reach past it.
        assertTrue(SeatPose.footPastEdge(7 / 16.0) > 0);
    }

    @Test void sittingBodyStartsAtTheFrontSpotAndEndsDroppedOnTheSeat() {
        assertArrayEquals(FRONT, drawn(true, 0, TOP, FRONT));
        assertArrayEquals(new double[]{TOP[0], TOP[1] - SeatPose.DROP, TOP[2]}, drawn(true, 1, TOP, FRONT));
    }

    @Test void risingBodyStartsDroppedOnTheSeatAndEndsAtTheEntity() {
        assertArrayEquals(new double[]{TOP[0], TOP[1] - SeatPose.DROP, TOP[2]}, drawn(false, 1, FRONT, TOP));
        assertArrayEquals(FRONT, drawn(false, 0, FRONT, TOP));
    }

    @Test void turningFromSitToRiseKeepsTheBodyWhereItWas() {
        for (double s : new double[]{.2, .5, .9}) {
            // The entity hops between the two ends in one tick; the drawn body must not.
            assertArrayEquals(drawn(true, s, TOP, FRONT), drawn(false, s, FRONT, TOP), 1e-9, "s=" + s);
        }
    }

    private static void assertArrayEquals(double[] a, double[] b) { assertArrayEquals(a, b, 1e-9, ""); }

    private static void assertArrayEquals(double[] a, double[] b, double delta, String message) {
        for (int i = 0; i < 3; i++) assertEquals(a[i], b[i], delta, message + " axis " + i);
    }
}
