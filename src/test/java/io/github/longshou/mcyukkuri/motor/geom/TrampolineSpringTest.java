package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrampolineSpringTest {
    @Test void restingContactStaysStillAndPassiveBouncesDecay() {
        double restitution = TrampolineSpring.RESTITUTION;
        assertEquals(0, TrampolineSpring.rebound(0, restitution, Ballistic.GRAVITY));
        assertEquals(0, TrampolineSpring.rebound(Ballistic.GRAVITY, restitution, Ballistic.GRAVITY));
        double first = TrampolineSpring.rebound(.65, restitution, Ballistic.GRAVITY);
        double next = TrampolineSpring.rebound(first, restitution, Ballistic.GRAVITY);
        assertTrue(first < .65);
        assertTrue(next < first);
        assertTrue(TrampolineSpring.livingHeight(next) < TrampolineSpring.livingHeight(first));
    }

    @Test void heavinessTirednessAndYouthEachLowerTheBounce() {
        double adult = height(.30, 1, false);
        assertTrue(height(.40, 1, false) < adult);
        assertTrue(height(.30, .55, false) < adult);
        assertTrue(height(.08, 1, true) < adult);
        assertTrue(height(.08, 1, true) > 0);
    }

    @Test void aTimedJumpAddsToTheRebound() {
        assertEquals(.42, TrampolineSpring.jump(.42, 0), 1e-9);
        assertEquals(.42, TrampolineSpring.jump(.42, -.08), 1e-9);
        assertEquals(.72, TrampolineSpring.jump(.42, .30), 1e-9);
    }

    private static double height(double weight, double fitness, boolean young) {
        double restitution = TrampolineSpring.restitution(weight, fitness, young);
        return TrampolineSpring.livingHeight(TrampolineSpring.rebound(.65, restitution, Ballistic.GRAVITY));
    }
}
