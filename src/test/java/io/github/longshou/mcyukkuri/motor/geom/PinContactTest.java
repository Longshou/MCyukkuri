package io.github.longshou.mcyukkuri.motor.geom;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PinContactTest {
    private final Vec forward = new Vec(0, 0, 1);

    @Test void rearContactSurvivesSmallLateralMotionWithoutAnyPositionCorrection() {
        assertTrue(PinContact.touches(new Vec(.3, 0, -1), forward, 1, 1));
        assertTrue(PinContact.touches(new Vec(.3, 0, -1.2), forward, 1, 1));
    }

    @Test void frontOverlapAndEscapedLandingAreNotContact() {
        assertFalse(PinContact.touches(new Vec(0, 0, 1), forward, 1, 1));
        assertFalse(PinContact.touches(new Vec(0, 0, -.9), forward, 1, 1));
        assertFalse(PinContact.touches(new Vec(0, 0, -1.3), forward, 1, 1));
        assertFalse(PinContact.touches(new Vec(0, .1, -1), forward, 1, 1));
    }

    @Test void ContactToleranceScalesWithTheSmallerBody() {
        assertTrue(PinContact.touches(new Vec(0, 0, -.6), forward, .5, .5));
        assertFalse(PinContact.touches(new Vec(0, 0, -.7), forward, .5, .5));
    }
}
