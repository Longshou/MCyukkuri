package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeparationStepTest {
    @Test void opensTheGapFromTheArchivedPeerRubStandWhenAStandTurnsInward() {
        double x = -.9443758;
        double clearance = .8 + .6 * .5;
        Vec turnIn = new Vec(.0335, -.0784, -.1116);
        for (int step = 0; step < 5; step++) {
            Vec moved = SeparationStep.away(x, -.1, clearance, turnIn);
            assertTrue(x + moved.x() <= -Math.min(-x, clearance));
            assertTrue(moved.hlen() <= turnIn.hlen() + 1e-12);
            assertEquals(turnIn.y(), moved.y());
            x += moved.x();
        }
        assertTrue(-x >= clearance);
    }

    @Test void aSweepCannotCrossThePartnerEvenIfItsEndpointIsClear() {
        Vec moved = SeparationStep.away(1.04, 0, 1, new Vec(-2.1, 0, 0));
        assertEquals(0, moved.x());
        assertTrue(Math.abs(moved.z()) > 0);
    }

    @Test void passesTangentAndOutwardTravelThroughUnchangedOnceClear() {
        Vec wanted = new Vec(-.1, -.08, .01);
        assertSame(wanted, SeparationStep.away(-1.04, .5, 1, wanted));
        assertSame(wanted, SeparationStep.away(.6, 1.04, 1, wanted));
    }
}
