package io.github.longshou.mcyukkuri.family;

import static io.github.longshou.mcyukkuri.family.PentUpMath.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PentUpMathTest {
    @Test void onlyFertileAdultsOutsideStockAndPredatorsBuild() {
        assertTrue(eligible(true, false, false, true, false));
        assertFalse(eligible(false, false, false, true, false));
        assertFalse(eligible(true, true, false, true, false));
        assertFalse(eligible(true, false, true, true, false));
        assertFalse(eligible(true, false, false, false, false));
        assertFalse(eligible(true, false, false, true, true));
    }

    @Test void conditionAndSightScaleTheLoadedDay() {
        float base = rate(true, true, false, false, false, false, false);
        assertEquals(40, base * 24000, .0001);
        assertEquals(base / 2, rate(false, true, false, false, false, false, false));
        assertEquals(base / 2, rate(true, false, false, false, false, false, false));
        assertEquals(base / 2, rate(true, true, true, false, false, false, false));
        assertEquals(base * 1.5f, rate(true, true, false, false, false, true, false));
        assertEquals(base * 2, rate(true, true, false, false, false, false, true));
        assertEquals(0, rate(false, true, false, true, false, true, true));
        assertEquals(0, rate(true, false, false, false, true, true, true));
        assertEquals(1.4375, band(.5) / (base * 24000), .0001);
        assertEquals(2.875, band(.5) / (base * .5 * 24000), .0001);
    }

    @Test void patienceShiftsOnsetAndACappedBodyCanStartAfterCooldown() {
        assertEquals(45, band(0));
        assertEquals(57.5f, band(.5));
        assertEquals(70, band(1));
        assertEquals(Episode.NONE, episode(57.49f, .5, false, true, 0, 0));
        assertEquals(Episode.HEAT, episode(57.5f, .5, false, true, 0, 0));
        assertEquals(Episode.NONE, episode(100, 1, false, true, 1, 0));
        assertEquals(Episode.HEAT, episode(100, 1, false, true, 0, 0));
        assertEquals(Episode.NONE, episode(100, 1, false, false, 0, 0));
    }

    @Test void onlyAnEligibleImpulsiveAlicePrioritizesTheHunt() {
        assertEquals(Episode.HEAT, episode(89.9f, 0, true, true, 0, 0));
        assertEquals(Episode.HUNT, episode(90, 0, true, true, 0, 0));
        assertEquals(Episode.HEAT, episode(90, 1, false, true, 0, 0));
        assertEquals(Episode.NONE, episode(90, 0, true, true, 0, 1));
        assertEquals(Episode.NONE, episode(90, 0, true, true, 1, 0));
        assertEquals(Episode.NONE, episode(0, 0, true, true, 0, 0));
    }

    @Test void anUnmetPeriodRetainsItsGainAndStimulationStartsFromAtLeastTheBand() {
        float unmet = add(80, 1200 * rate(true, true, false, false, false, false, false));
        assertEquals(82, unmet);
        assertEquals(84, add(unmet, HELD));
        assertEquals(86, add(84, VIBRATE));
        assertEquals(87.5f, stimulant(20, .5));
        assertEquals(95, stimulant(65, .5));
        assertEquals(100, stimulant(unmet, .5));
        assertEquals(100, add(96, WITNESS));
    }

    @Test void aCompletedPairRelievesBothAndStimulatesEachBystanderOnce() {
        var relieved = new ArrayList<String>();
        var witnesses = new ArrayList<String>();
        var audience = List.of("actor", "peer", "near", "other");
        completed("actor", "peer", true, audience, relieved::add, witnesses::add);
        assertEquals(List.of("actor", "peer"), relieved);
        assertEquals(List.of("near", "other"), witnesses);
        relieved.clear(); witnesses.clear();
        completed("actor", "peer", false, audience, relieved::add, witnesses::add);
        assertEquals(List.of("actor"), relieved);
        assertEquals(List.of("near", "other"), witnesses);
    }
}
