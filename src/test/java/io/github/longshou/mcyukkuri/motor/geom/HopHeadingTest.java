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
}
