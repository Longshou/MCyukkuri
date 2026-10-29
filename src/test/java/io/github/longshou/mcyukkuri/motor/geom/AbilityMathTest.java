package io.github.longshou.mcyukkuri.motor.geom;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 8.1 and 14.8 ({@code AbilityFormulaTest}): the numbers come from the coefficients, not from call sites. */
class AbilityMathTest {
    private static final double EPS = 1e-9;
    private static final AbilityMath.Coefficients C = AbilityMath.Coefficients.DEFAULTS;
    private static final AbilityMath.Multipliers NONE = AbilityMath.Multipliers.IDENTITY;

    private static AbilityMath.Values adult(AbilityMath.Inputs in) {
        return AbilityMath.compute(in, C, NONE);
    }

    /** The reference adult of spec 8.1 with one field replaced. */
    private static AbilityMath.Inputs adultWith(double size, double energy, double health, double pain,
                                                 boolean burned, boolean canJump, boolean dying, boolean sleeping,
                                                 double load) {
        return new AbilityMath.Inputs(size, energy, health, 20, pain, burned, canJump, dying, sleeping, load);
    }

    @Test void adultInFullConditionJumpsPointEightAndReachesOnePointZero() {
        var v = adult(AbilityMath.Inputs.ADULT);
        assertTrue(v.canJump());
        assertEquals(.80, v.hopHeight(), EPS);
        assertEquals(1.00, v.hopReach(), EPS);
        assertEquals(1, v.size(), EPS);
        assertEquals(1, v.fitness(), EPS);
        assertEquals(1, v.load(), EPS);
        assertEquals(.60, v.stepUp(), EPS);
        assertEquals(.10, v.walkSpeed(), EPS);
        assertEquals(12, v.turnRate(), EPS);
        assertEquals(.03, v.swimSpeed(), EPS);
        assertEquals(.75, v.climbRise(), EPS);
        assertEquals(1, v.grip(), EPS);
    }

    @Test void underEightEnergyCannotJump() {
        var tired = adult(adultWith(1, 7.9, 20, 0, false, true, false, false, 0));
        assertFalse(tired.canJump());
        assertEquals(0, tired.hopHeight(), EPS);
        assertEquals(0, tired.hopReach(), EPS);
        assertEquals(.60, tired.stepUp(), EPS);
    }

    @Test void exactlyEightEnergyStillJumps() {
        var ready = adult(adultWith(1, 8, 20, 0, false, true, false, false, 0));
        assertTrue(ready.canJump());
        assertTrue(ready.hopHeight() > 0);
        assertTrue(ready.hopReach() > 0);
    }

    @Test void dyingSleepingAndChildhoodCannotJump() {
        assertFalse(adult(adultWith(1, 80, 20, 0, false, true, true, false, 0)).canJump());
        assertFalse(adult(adultWith(1, 80, 20, 0, false, true, false, true, 0)).canJump());
        assertFalse(adult(adultWith(1, 80, 20, 0, false, false, false, false, 0)).canJump());
    }

    @Test void injuryAndLowEnergyOnlyReduceValues() {
        var whole = adult(AbilityMath.Inputs.ADULT);
        var hurt = adult(adultWith(1, 80, 20, 3, false, true, false, false, 0));
        var burned = adult(adultWith(1, 80, 20, 0, true, true, false, false, 0));
        var hungry = adult(adultWith(1, 32, 20, 0, false, true, false, false, 0));
        var wounded = adult(adultWith(1, 80, 8, 0, false, true, false, false, 0));
        for (var weaker : java.util.List.of(hurt, burned, hungry, wounded)) {
            assertTrue(weaker.fitness() < whole.fitness());
            assertTrue(weaker.hopHeight() < whole.hopHeight());
            assertTrue(weaker.hopReach() < whole.hopReach());
            assertTrue(weaker.walkSpeed() < whole.walkSpeed());
            assertTrue(weaker.climbRise() < whole.climbRise());
            assertTrue(weaker.swimSpeed() < whole.swimSpeed());
            assertTrue(weaker.turnRate() < whole.turnRate());
            assertTrue(weaker.grip() < whole.grip());
        }
    }

    @Test void sizeScalesLimitsAndSmallBodiesStayAboveTheFloor() {
        assertEquals(.5, adult(adultWith(.25, 80, 20, 0, false, true, false, false, 0)).size(), EPS);
        assertEquals(.40, adult(adultWith(.25, 80, 20, 0, false, true, false, false, 0)).hopHeight(), EPS);
        assertEquals(.30, adult(adultWith(.25, 80, 20, 0, false, true, false, false, 0)).stepUp(), EPS);
        // Below the clamp the size never vanishes, so a hatchling still has a (tiny) hop and a step.
        var tiny = adult(adultWith(0, 80, 20, 0, false, true, false, false, 0));
        assertEquals(Math.sqrt(.06), tiny.size(), EPS);
        assertTrue(tiny.hopHeight() > 0);
    }

    @Test void loadReducesWalkAndSwimOnly() {
        var loaded = adult(adultWith(1, 80, 20, 0, false, true, false, false, 12));
        var free = adult(AbilityMath.Inputs.ADULT);
        assertEquals(.5, loaded.load(), EPS);
        assertEquals(.05, loaded.walkSpeed(), EPS);
        assertEquals(.015, loaded.swimSpeed(), EPS);
        assertEquals(free.hopHeight(), loaded.hopHeight(), EPS);
        assertEquals(free.hopReach(), loaded.hopReach(), EPS);
        assertEquals(free.stepUp(), loaded.stepUp(), EPS);
        assertEquals(free.climbRise(), loaded.climbRise(), EPS);
        assertEquals(free.turnRate(), loaded.turnRate(), EPS);
    }

    @Test void speciesMultipliersApplyPerCoefficient() {
        var aquatic = AbilityMath.compute(AbilityMath.Inputs.ADULT, C,
                new AbilityMath.Multipliers(Map.of("swim_speed", 1.5, "hat_boat", 1.0)));
        assertEquals(.045, aquatic.swimSpeed(), EPS);
        assertEquals(.10, aquatic.walkSpeed(), EPS);
        assertEquals(.80, aquatic.hopHeight(), EPS);
        assertTrue(new AbilityMath.Multipliers(Map.of("hat_boat", 1.0)).flag("hat_boat"), "a species flag is on when authored above 0");
        assertFalse(AbilityMath.Multipliers.IDENTITY.flag("hat_boat"), "and off where the data authors none");
        assertEquals(.75, aquatic.climbRise(), EPS);

        var climber = AbilityMath.compute(AbilityMath.Inputs.ADULT, C,
                new AbilityMath.Multipliers(Map.of("hop_height", .5)));
        assertEquals(.40, climber.hopHeight(), EPS);
        assertEquals(1.00, climber.hopReach(), EPS);
    }

    @Test void dataCoefficientsReplaceEveryNumber() {
        var doubled = new AbilityMath.Coefficients(1.60, 2.00, 1.2, .20, 24, .6, .11, 1.5, .65, 80, 8, .18, .3, 12,
                .06, 1.5, .35);
        var v = AbilityMath.compute(AbilityMath.Inputs.ADULT, doubled, NONE);
        assertEquals(1.60, v.hopHeight(), EPS);
        assertEquals(2.00, v.hopReach(), EPS);
        assertEquals(1.2, v.stepUp(), EPS);
        assertEquals(.20, v.walkSpeed(), EPS);
        assertEquals(.11, v.swimSpeed(), EPS);
    }

    @Test void nullTableFallsBackToDefaults() {
        var v = AbilityMath.compute(AbilityMath.Inputs.ADULT, null, null);
        assertEquals(.80, v.hopHeight(), EPS);
        assertEquals(1.00, v.hopReach(), EPS);
    }
}
