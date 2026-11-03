package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Body push shares, turn, and body lift. */
class EffortTest {
    @Test void theLighterOneMovesMore() {
        assertEquals(.75, Effort.share(.3, .1), 1e-12);
        assertEquals(.25, Effort.share(.1, .3), 1e-12);
        assertEquals(0, Effort.share(0, 0), 1e-12);
    }

    @Test void turnsTheShortWayAndNoFasterThanTheRate() {
        assertEquals(10, Effort.turn(0, 10, 12), 1e-9);
        assertEquals(12, Effort.turn(0, 90, 12), 1e-9);
        assertEquals(-12, Effort.turn(0, -90, 12), 1e-9);
        assertEquals(-10, Effort.turn(175, 165, 12), 1e-9);
        assertEquals(10, Effort.turn(175, -175, 12), 1e-9, "across ±180 the short way");
        assertEquals(0, Effort.turn(30, 30, 12), 1e-9);
    }

    @Test void aBodyFloatsNeutralNearHalfUnder() {
        assertEquals(.005, Buoyancy.bodyLift(.56, 0), 1e-12, "balances vanilla water gravity (0.08 / 16)");
        assertTrue(Buoyancy.bodyLift(.9, 0) > .005);
        assertTrue(Buoyancy.bodyLift(.2, 0) < .005);
        assertEquals(.032, Buoyancy.bodyLift(1, -1), 1e-12);
    }

    @Test void aMovingFallerDoesNotHideTheHullsApproach() {
        double advanced = 0;
        for (int tick = 1; tick <= 40; tick++)
            advanced += Effort.approach((tick - 1) * .015, 0, tick * .015, 0, 3 + tick * .015, 0);
        assertEquals(.6, advanced, 1e-9, "equal swimming speeds preserve separation while the hull advances");
    }

    @Test void aPinnedHullEarnsNothingFromTargetMovement() {
        for (int tick = 1; tick <= 40; tick++)
            assertEquals(0, Effort.approach(0, 0, 0, 0, 3 - tick * .015, 1), 1e-9);
        assertTrue(Effort.approach(0, 0, -.1, 0, 3, 0) < 0, "drifting away loses progress");
        assertEquals(0, Effort.approach(0, 1, 0, -1, 3, 0), 1e-9, "sideways travel alone is not approach");
    }
}
