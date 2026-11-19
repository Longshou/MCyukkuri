package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.motor.Reason;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BraidGripTest {
    @Test void reachWithoutFootingRefusesGrip() {
        var grip = new BraidGrip(false, -.1, -.2);
        assertFalse(grip.ready());
        assertEquals(Reason.NO_SUPPORT, grip.check().reason());
        assertEquals("unstable_grip_stand", grip.check().detail());
    }

    @Test void footingWithoutReachReportsTheNearerGap() {
        var grip = new BraidGrip(true, .2, .1);
        assertFalse(grip.ready());
        assertEquals(Reason.NO_CONTACT, grip.check().reason());
        assertEquals(.1, grip.check().shortfall());
    }

    @Test void supportedGripPrefersUprightAndAcceptsTheLowLeanAtContact() {
        var upright = new BraidGrip(true, 0, -.2);
        assertTrue(upright.ready());
        assertTrue(upright.check().ok());
        assertFalse(upright.lean());

        var low = new BraidGrip(true, .1, 0);
        assertTrue(low.ready());
        assertTrue(low.check().ok());
        assertTrue(low.lean());
    }
}
