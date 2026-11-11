package io.github.longshou.mcyukkuri.family;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StemDrainTest {
    @Test void drawsForGrowthAndProratesOnlyAtTerm() {
        assertEquals(0, StemDrain.drawTicks(199, 450));
        assertEquals(200, StemDrain.drawTicks(200, 450));
        assertEquals(0, StemDrain.drawTicks(449, 450));
        assertEquals(50, StemDrain.drawTicks(450, 450));
        assertEquals(0, StemDrain.drawTicks(451, 450));
        assertEquals(0, StemDrain.drawTicks(600, 450));
    }

    @Test void fruitCountAndStalkSizeIncreaseTheDraw() {
        assertEquals(1.116f, StemDrain.draw(200, 1, .93f), .00001f);
        assertEquals(2.14f, StemDrain.draw(200, 5, 1.07f), .00001f);
        assertEquals(.535f, StemDrain.draw(50, 5, 1.07f), .00001f);
    }

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
