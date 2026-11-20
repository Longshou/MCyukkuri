package io.github.longshou.mcyukkuri.settlement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterPreferenceTest {
    @Test void aVisibleCartonCanDrawATiredBabyBeyondItsWanderingRange() {
        double need = ShelterPreference.need(48, false, 0, 0);
        assertTrue(ShelterPreference.worthMoving(need, 48, true, 3.33, .12));
        assertFalse(ShelterPreference.worthMoving(need, 48, true, Double.POSITIVE_INFINITY, 0));
    }

    @Test void aWaryStrayKeepsCoverWhileTheHumanIsClose() {
        double need = ShelterPreference.need(48, false, 0, 0);
        assertFalse(ShelterPreference.worthMoving(need, 48, true, 3.33, .46));
        assertTrue(ShelterPreference.worthMoving(need, 48, true, 3.33, 0));
    }

    @Test void exposureCanJustifyTheSameWalkThatAWatchfulRestWouldDecline() {
        double comfortable = ShelterPreference.need(80, true, 0, 0);
        double cold = ShelterPreference.need(80, true, .6, 0);
        double wet = ShelterPreference.need(80, true, 0, 60);
        assertTrue(ShelterPreference.worthMoving(comfortable, 80, false, 3, .15));
        assertFalse(ShelterPreference.worthMoving(comfortable, 80, true, 3, .15));
        assertTrue(ShelterPreference.worthMoving(cold, 80, true, 3, .15));
        assertTrue(ShelterPreference.worthMoving(wet, 80, true, 3, .15));
    }

    @Test void lowEnergyMakesADetourCostlierAtTheSameShelterNeed() {
        assertTrue(ShelterPreference.worthMoving(.5, 70, true, 5, 0));
        assertFalse(ShelterPreference.worthMoving(.5, 15, true, 5, 0));
    }
}
