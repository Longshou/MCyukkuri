package io.github.longshou.mcyukkuri.pose;

import io.github.longshou.mcyukkuri.motor.geom.ContactStandSearch;
import io.github.longshou.mcyukkuri.motor.geom.Detour;
import java.util.List;
import java.util.function.Predicate;
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

    @Test void theRecordedDiagonalRubUsesItsPosedContactInsideThePartnersWalkingPadding() {
        // MD-28 west at t5840. The child is the stationary left pose, the adult moves on the right.
        var child = SnugglePose.at("reimu", false, 57);
        var adult = SnugglePose.at("reimu", true, 57);
        var contact = SideSurface.meet(child, .4 / 16, adult, 1 / 16.0, 0, 0);
        var yaw = Math.toRadians(244.16423);
        var x = -0.277885;
        var z = -0.573917;
        assertEquals(x, contact.separation() * Math.cos(yaw), 5e-7);
        assertEquals(z, contact.separation() * Math.sin(yaw), 5e-7);
        assertTrue(contact.touches(contact.separation()));
        assertFalse(contact.touches(contact.separation() - .001));

        double padded = .575000;
        var partner = Detour.Box.aligned(1, -padded, -padded, padded, padded);
        var bystander = Detour.Box.aligned(2, -padded, -padded, padded, padded);
        Predicate<Detour.Box> crosses = box -> box.gap(x, z) < 0;
        assertSame(partner, ContactStandSearch.blockingFootprint(List.of(partner), null, crosses));
        assertNull(ContactStandSearch.blockingFootprint(List.of(partner), partner, crosses));
        assertSame(bystander, ContactStandSearch.blockingFootprint(List.of(partner, bystander), partner, crosses));
    }
}
