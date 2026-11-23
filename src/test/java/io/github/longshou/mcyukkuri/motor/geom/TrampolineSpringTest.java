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

    @Test void reboundForHeightMatchesLivingHeight() {
        for (double target : new double[]{.25, .45, .75, 1.0}) {
            double speed = TrampolineSpring.reboundForHeight(target);
            assertEquals(target, TrampolineSpring.livingHeight(speed), 1e-3);
        }
        assertEquals(0, TrampolineSpring.reboundForHeight(0), 1e-9);
    }

    @Test void activePlaySustainsHeightAndReflectsStats() {
        double lightFit = TrampolineSpring.restitution(.22, 1.0, false);
        double heavyTired = TrampolineSpring.restitution(.45, .80, false);
        double boldFirst = TrampolineSpring.targetApex(.76, lightFit, .95, 0);
        double boldPeak = TrampolineSpring.targetApex(.76, lightFit, .95, 2);
        double timidPeak = TrampolineSpring.targetApex(.73, heavyTired, .05, 2);
        assertTrue(boldPeak > boldFirst);
        assertTrue(boldPeak > timidPeak);
        assertTrue(timidPeak > .20);
        double hopSpeed = ArcSolver.vyFor(.76);
        double targetSpeed = TrampolineSpring.reboundForHeight(boldPeak);
        double sustained = TrampolineSpring.activeRebound(.35, lightFit, Ballistic.GRAVITY, hopSpeed, targetSpeed);
        assertEquals(boldPeak, TrampolineSpring.livingHeight(sustained), 1e-3);
        // A body that cannot hop gets only passive rebound (0 on standing contact).
        assertEquals(0, TrampolineSpring.activeRebound(Ballistic.GRAVITY, lightFit, Ballistic.GRAVITY, 0, targetSpeed));
    }

    private static double height(double weight, double fitness, boolean young) {
        double restitution = TrampolineSpring.restitution(weight, fitness, young);
        return TrampolineSpring.livingHeight(TrampolineSpring.rebound(.65, restitution, Ballistic.GRAVITY));
    }
}
