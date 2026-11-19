package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndulgenceTest {
    @Test
    void ordinaryMealOrRefusedSweetDoesNotIndulgeAnOpenDemand() {
        assertFalse(Indulgence.sweet(false, false, true, false, 30));
        assertFalse(Indulgence.sweet(false, false, true, true, 96));
    }

    @Test
    void earnedRewardsAndOccasionalTreatsAreExempt() {
        assertFalse(Indulgence.sweet(true, true, true, true, 90));
        assertFalse(Indulgence.sweet(true, false, false, false, 75));
    }

    @Test
    void demandAndSurplusClassifyTheSameServingOnce() {
        assertTrue(Indulgence.sweet(true, false, true, false, 50));
        assertTrue(Indulgence.sweet(true, false, false, true, 50));
        assertTrue(Indulgence.sweet(true, false, false, false, 76));
        assertTrue(Indulgence.sweet(true, false, true, true, 90));
    }
}
