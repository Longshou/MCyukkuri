package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Water effort: push shares, the paddle's stroke, thrust and turn, righting, and the body's lift. */
class EffortTest {
    @Test void theLighterOneMovesMore() {
        assertEquals(.75, Effort.share(.3, .1), 1e-12);
        assertEquals(.25, Effort.share(.1, .3), 1e-12);
        assertEquals(0, Effort.share(0, 0), 1e-12);
    }

    @Test void onlyThePowerStrokeDrives() {
        assertEquals(0, Effort.stroke(3, 4, 20, 32), 0);
        assertEquals(1, Effort.stroke(4, 4, 20, 32), 0);
        assertEquals(1, Effort.stroke(19, 4, 20, 32), 0);
        assertEquals(0, Effort.stroke(20, 4, 20, 32), 0);
        assertEquals(1, Effort.stroke(32 + 10, 4, 20, 32), 0, "every cycle the same");
    }

    @Test void steadyStrokesSettleAtThePaddlersPaceTimesItsShare() {
        double v = 0, push = Effort.drive(.11, .8, .88);
        for (int i = 0; i < 400; i++) v = (v + push) * .88;
        assertEquals(.11 * .8, v, 1e-9);
        assertEquals(0, Effort.drive(.11, .8, 0), 0);
    }

    @Test void turnsTheShortWayAndNoFasterThanTheRate() {
        assertEquals(10, Effort.turn(0, 10, 12), 1e-9);
        assertEquals(12, Effort.turn(0, 90, 12), 1e-9);
        assertEquals(-12, Effort.turn(0, -90, 12), 1e-9);
        assertEquals(-10, Effort.turn(175, 165, 12), 1e-9);
        assertEquals(10, Effort.turn(175, -175, 12), 1e-9, "across ±180 the short way");
        assertEquals(0, Effort.turn(30, 30, 12), 1e-9);
    }

    @Test void rightingTakesHalfARoundOfTheBrim() {
        double step = Effort.righting(.11, .72, .8);
        assertEquals(.11 * .72 / (Math.PI * .8), step, 1e-12);
        int ticks = (int) Math.ceil(1 / step);
        assertTrue(ticks > 20 && ticks < 40, "an adult needs a few clip pushes, not one: " + ticks);
        assertEquals(0, Effort.righting(.11, .72, 0), 0);
    }

    @Test void aBodyFloatsNeutralNearHalfUnder() {
        assertEquals(.005, Buoyancy.bodyLift(.56, 0), 1e-12, "balances vanilla water gravity (0.08 / 16)");
        assertTrue(Buoyancy.bodyLift(.9, 0) > .005);
        assertTrue(Buoyancy.bodyLift(.2, 0) < .005);
        assertEquals(.032, Buoyancy.bodyLift(1, -1), 1e-12);
    }
}
