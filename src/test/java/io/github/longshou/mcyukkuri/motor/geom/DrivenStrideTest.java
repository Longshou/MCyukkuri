package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrivenStrideTest {
    @Test void blockedRunCannotBorrowShoveRecoilOrKnockback() {
        var stride = new DrivenStride();
        stride.drive(1, .04, 0, 0, .04);
        stride.resolved(1, .04, .3, .12, 0, .3, .12);
        assertFalse(stride.ran(1));
        // Even a push in the requested direction can account for the entire clipped step.
        stride.drive(2, .04, 0, 0, .04);
        stride.resolved(2, .14, 0, 0, .09, 0, 0);
        assertFalse(stride.ran(2));
        stride.resolved(2, .14, 0, 0, .14, 0, 0);
        assertFalse(stride.ran(2));
    }

    @Test void shortFinalAdjustmentAndPassiveFlightDoNotSpendAStroke() {
        var stride = new DrivenStride();
        stride.drive(1, .002, 0, 0, .09);
        stride.resolved(1, .002, 0, 0, .002, 0, 0);
        assertFalse(stride.ran(1));
        stride.drive(2, 0, -.02, 0, .02);
        stride.resolved(2, 0, .1, 0, 0, .1, 0);
        assertFalse(stride.ran(2));
        stride.resolved(3, 0, .16, 0, 0, .16, 0);
        assertFalse(stride.ran(3));
    }

    @Test void realGroundCrawlAndWingPropulsionSurviveSuperimposedMotion() {
        var stride = new DrivenStride();
        stride.drive(1, .04, 0, 0, .04);
        stride.resolved(1, .10, -.08, 0, .10, 0, 0);
        assertTrue(stride.ran(1));
        stride.drive(2, 0, 0, -.006, .006);
        stride.resolved(2, .05, 0, -.02, .05, 0, -.02);
        assertTrue(stride.ran(2));
        stride.drive(3, 0, .018, 0, .018);
        stride.resolved(3, 0, .2, 0, 0, .2, 0);
        assertTrue(stride.ran(3));
        assertFalse(stride.ran(4));
    }
}
