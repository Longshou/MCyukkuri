package io.github.longshou.mcyukkuri.family;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StemDrainTest {
    @Test void drawsForGrowthAndProratesOnlyAtTerm() {
        assertEquals(0, StemDrain.drawTicks(19, 407, 20));
        assertEquals(20, StemDrain.drawTicks(20, 407, 20));
        assertEquals(0, StemDrain.drawTicks(406, 407, 20));
        assertEquals(7, StemDrain.drawTicks(407, 407, 20));
        assertEquals(0, StemDrain.drawTicks(408, 407, 20));
        assertEquals(50, StemDrain.drawTicks(450, 450, 200));
    }

    @Test void fruitCountAndStalkSizeIncreaseTheDraw() {
        assertEquals(372, StemDrain.duration(.93f));
        assertEquals(428, StemDrain.duration(1.07f));
        assertEquals(33.48f, StemDrain.draw(372, 1, .93f, 372, true), .00001f);
        assertEquals(64.2f, StemDrain.draw(428, 5, 1.07f, 428, true), .00001f);
        assertEquals(.535f, StemDrain.draw(50, 5, 1.07f, 450, false), .00001f);
    }

    @Test void depletionGraceDoesNotHealOrPreventOtherDamage() {
        assertEquals(.25f, StemDrain.damage(3,1.25f,59,0), .00001f);
        assertEquals(0, StemDrain.damage(3,.5f,59,0));
        assertEquals(.6f, StemDrain.damage(3,.5f,60,0), .00001f);
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
