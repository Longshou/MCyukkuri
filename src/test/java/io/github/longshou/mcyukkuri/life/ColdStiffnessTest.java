package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColdStiffnessTest {
    private static final double COLD = .2, FREEZE = 0, T = 2400, BASE = .75;

    @Test void anAdultOnAWinterNightHasAboutAMinute() {
        double share = ColdStiffness.windowShare(BASE, 60, 1, 1);
        int left = ColdStiffness.windowTicks(T, share, 1, COLD - -1.2);
        assertTrue(left > 1000 && left < 1500, "typical adult window in ticks: " + left);
    }

    @Test void theSameColdGivesEachBodyItsOwnWindow() {
        int adult = ColdStiffness.windowTicks(T, ColdStiffness.windowShare(BASE, 60, 1, 1), 1, 1.4);
        int baby = ColdStiffness.windowTicks(T, ColdStiffness.windowShare(BASE, 60, .35, 1), 2, 1.4);
        int hungry = ColdStiffness.windowTicks(T, ColdStiffness.windowShare(BASE, 10, 1, 1), 1, 1.4);
        int fat = ColdStiffness.windowTicks(T, ColdStiffness.windowShare(BASE, 100, 1.2, 1), 1, 1.4);
        int hurt = ColdStiffness.windowTicks(T, ColdStiffness.windowShare(BASE, 60, 1, .2), 1, 1.4);
        assertTrue(baby < adult / 2 + 1, "a baby chills twice as fast and is small: " + baby + " vs " + adult);
        assertTrue(baby < 1200, "a baby has well under a minute: " + baby);
        assertTrue(hungry < adult && hurt < adult && fat > adult);
    }

    @Test void theShareStaysInItsIntervalAndTheWindowUnderTheCeiling() {
        assertEquals(ColdStiffness.MIN_SHARE, ColdStiffness.windowShare(BASE, 0, 0, 0), 1e-9);
        assertEquals(ColdStiffness.MAX_SHARE, ColdStiffness.windowShare(3, 100, 4, 1), 1e-9);
        assertEquals(ColdStiffness.MAX_LEFT, ColdStiffness.windowTicks(1_728_000, 1.4, 1, .3));
    }

    @Test void aShallowDeficitIsCountedAsAtLeastThreeTenths() {
        assertEquals(ColdStiffness.windowTicks(T, .72, 1, .3), ColdStiffness.windowTicks(T, .72, 1, .05));
    }

    @Test void theWindowDrainsInTheFreezeSlowlyBelowTheColdLineAndRefillsInTheWarm() {
        assertEquals(-20, ColdStiffness.change(-.5, COLD, FREEZE, .5), 1e-9);
        assertEquals(-20, ColdStiffness.change(FREEZE, COLD, FREEZE, .5), 1e-9);
        assertEquals(-5, ColdStiffness.change(.1, COLD, FREEZE, .5), 1e-9);
        assertEquals(0, ColdStiffness.change(COLD, COLD, FREEZE, .5), 1e-9);
        double torch = ColdStiffness.change(COLD + .4, COLD, FREEZE, .5) / 20;
        double fire = ColdStiffness.change(COLD + 1.2, COLD, FREEZE, .5) / 20;
        assertEquals(.4, torch, 1e-9);
        assertEquals(1, fire, 1e-9);
    }

    @Test void aFasterThawRateRefillsFaster() {
        assertTrue(ColdStiffness.change(1.2, COLD, FREEZE, 1) > ColdStiffness.change(1.2, COLD, FREEZE, .5));
    }

    @Test void theWindowNeverRefillsPastWhatItEnteredWith() {
        assertEquals(1000, ColdStiffness.advance(990, 1000, 20), 1e-9);
        assertEquals(500, ColdStiffness.advance(520, 1000, -20), 1e-9);
    }

    @Test void onlyAWarmSampleBackAtTheThawLineThaws() {
        assertFalse(ColdStiffness.thawed(1000, 1000, false), "a full window in the cold does not thaw");
        assertFalse(ColdStiffness.thawed(899, 1000, true));
        assertTrue(ColdStiffness.thawed(900, 1000, true));
        assertFalse(ColdStiffness.spent(1));
        assertTrue(ColdStiffness.spent(0));
    }

    @Test void aWarmFireRefillsFromTheMiddleOfAWindowInUnderAMinute() {
        double left = 300, max = 1200;
        int samples = 0;
        while (!ColdStiffness.thawed(left, max, true) && samples < 1000) {
            left = ColdStiffness.advance(left, max, ColdStiffness.change(COLD + 1.2, COLD, FREEZE, .5));
            samples++;
        }
        assertTrue(samples * 20 < 60 * 20, "ticks to thaw beside a fire: " + samples * 20);
    }

    @Test void aBodyThatCannotStiffenFreezesOnePastTheWindowOnTheExposure() {
        assertEquals(T * 1.72, ColdStiffness.deathExposure(T, .72), 1e-9);
    }

    @Test void noToolBreaksTheShellInOneSwingAndAPickaxeIsFasterThanAHand() {
        double hand = ColdStiffness.chiselHit(1, 1), sword = ColdStiffness.chiselHit(1.5, 1), wood = ColdStiffness.chiselHit(2, 1);
        double iron = ColdStiffness.chiselHit(6, 1), gold = ColdStiffness.chiselHit(12, 1);
        assertTrue(hand < sword && sword < wood && wood < iron, "tool order");
        assertEquals(iron, gold, 1e-9, "the cap: the best tools are all three swings");
        assertTrue(ColdStiffness.chiselHit(1000, 1) * 2 < ColdStiffness.CHISEL_FULL, "never fewer than three full swings");
        assertEquals(9, Math.ceil(ColdStiffness.CHISEL_FULL / hand), "a bare hand needs nine");
        assertEquals(3, Math.ceil(ColdStiffness.CHISEL_FULL / iron), "an iron pickaxe needs three");
    }

    @Test void aHalfRecoveredCooldownGivesHalfTheProgress() {
        assertEquals(ColdStiffness.chiselHit(1, 1) / 2, ColdStiffness.chiselHit(1, .5), 1e-9);
        assertEquals(0, ColdStiffness.chiselHit(6, 0), 1e-9);
    }

    @Test void theShellShowsThreeCrackLevelsAndBreaksAtTheFourthStep() {
        double quarter = ColdStiffness.CHISEL_FULL / 4;
        assertEquals(0, ColdStiffness.crackLevel(quarter - .01));
        assertEquals(1, ColdStiffness.crackLevel(quarter));
        assertEquals(2, ColdStiffness.crackLevel(2 * quarter));
        assertEquals(3, ColdStiffness.crackLevel(3 * quarter));
        assertEquals(3, ColdStiffness.crackLevel(ColdStiffness.CHISEL_FULL - .01));
        assertFalse(ColdStiffness.shattered(ColdStiffness.CHISEL_FULL - .01));
        assertTrue(ColdStiffness.shattered(ColdStiffness.CHISEL_FULL));
    }

    @Test void idleCracksMendOneLevelAtATimeDownToNothing() {
        double quarter = ColdStiffness.CHISEL_FULL / 4;
        assertEquals(2 * quarter, ColdStiffness.mended(3 * quarter + .5), 1e-9);
        assertEquals(quarter, ColdStiffness.mended(2 * quarter), 1e-9);
        assertEquals(0, ColdStiffness.mended(quarter + .5), 1e-9);
        assertEquals(0, ColdStiffness.mended(.5), 1e-9);
        double progress = ColdStiffness.CHISEL_FULL - .5;
        int steps = 0;
        while (progress > 0 && steps < 10) { progress = ColdStiffness.mended(progress); steps++; }
        assertEquals(3, steps, "three idle periods clear the worst cracks");
    }
}
