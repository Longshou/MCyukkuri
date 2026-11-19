package io.github.longshou.mcyukkuri.pose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SideSurfaceTest {
    private static final SideSurface.Pose LEFT = new SideSurface.Pose(new float[]{-1, 0, 0, -1, 1, 0, -1, 0, 1});
    private static final SideSurface.Pose RIGHT = new SideSurface.Pose(new float[]{1, 0, 0, 1, 1, 0, 1, 0, 1});

    @Test void aRiseNeedsACommonHeightAndDepthOnTheTriangles() {
        assertNull(SideSurface.meet(LEFT, 1, RIGHT, 1, .6, .6));
        var contact = SideSurface.meet(LEFT, 1, RIGHT, 1, .25, .25);
        assertEquals(2, contact.separation());
        assertTrue(contact.touches(2));
        assertFalse(contact.touches(2.02));
        assertFalse(contact.touches(1.98));
    }

    @Test void fittedCheeksStayInReachOfTheSameClipDuringTheFinalBeat() {
        for (double small : new double[]{1, .4}) {
            for (int tick : new int[]{0, 39, 79, 80, 139}) {
                var left = SnugglePose.at("reimu", false, tick);
                var right = SnugglePose.at("reimu", true, tick);
                var contact = SideSurface.meet(left, small / 16, right, 1 / 16.0, 0, 0);
                assertTrue(contact.separation() > (small + 1) * .4);
                assertFalse(contact.touches(contact.separation() + .22));
            }
        }
    }

    @Test void fittedAdultCheeksHaveNoSharedPointAcrossAFullBlock() {
        var left = SnugglePose.at("reimu", false, 39);
        var right = SnugglePose.at("reimu", true, 39);
        assertNull(SideSurface.meet(left, 1 / 16.0, right, 1 / 16.0, 1, 0));
    }
}
