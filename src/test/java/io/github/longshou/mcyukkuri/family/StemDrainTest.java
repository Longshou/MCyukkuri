package io.github.longshou.mcyukkuri.family;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StemDrainTest {
    @Test void theCarryingItselfIsTheFirstStage() {
        assertEquals(1, StemDrain.stage(true, true, 75, 60));
        assertEquals(1, StemDrain.stage(true, true, 50, 50));
    }

    @Test void thinningAndWeakFollowTheBearerNeeds() {
        assertEquals(2, StemDrain.stage(true, true, 49, 60));
        assertEquals(2, StemDrain.stage(true, true, 75, 49));
        assertEquals(3, StemDrain.stage(true, true, 29, 60));
        assertEquals(3, StemDrain.stage(true, true, 75, 24));
    }

    @Test void theDrainLookOutlivesTheBroodWhileTheBodyStaysLow() {
        assertEquals(2, StemDrain.stage(false, true, 45, 60));
        assertEquals(3, StemDrain.stage(false, true, 0, 10));
    }

    @Test void theLookFadesOnceSheEatsAndSleepsBackAboveTheThresholds() {
        assertEquals(0, StemDrain.stage(false, true, 50, 50));
        assertEquals(0, StemDrain.stage(false, true, 85, 90));
    }

    @Test void aBodyThatWasNeverDrainedShowsNoDrainLook() {
        assertEquals(0, StemDrain.stage(false, false, 10, 10));
    }
}
