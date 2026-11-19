package io.github.longshou.mcyukkuri.body;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MoldContagionTest {
    @Test void gainUsesVisibleTierAndSkinHygiene() {
        int[] base = {0, 1, 2, 3, 3};
        for (int tier = 0; tier < base.length; tier++) {
            assertEquals(base[tier] * 3, MoldContagion.gain(tier, 39));
            assertEquals(base[tier] * 2, MoldContagion.gain(tier, 40));
            assertEquals(base[tier] * 2, MoldContagion.gain(tier, 69));
            assertEquals(base[tier], MoldContagion.gain(tier, 70));
        }
    }

    @Test void continuousContactTakesMinutes() {
        assertEquals(180, secondsUntilCaught(2, 85));
        assertEquals(90, secondsUntilCaught(2, 50));
        assertEquals(60, secondsUntilCaught(2, 30));
        assertEquals(40, secondsUntilCaught(3, 30));
        assertEquals(360, secondsUntilCaught(1, 85));
    }

    private int secondsUntilCaught(int tier, int hygiene) {
        int exposure = 0, samples = 0;
        while (exposure < MoldContagion.THRESHOLD) {
            exposure = MoldContagion.next(exposure, MoldContagion.gain(tier, hygiene));
            samples++;
        }
        return samples * MoldContagion.SAMPLE / 20;
    }

    @Test void separationLosesOneSampleWithoutGoingNegative() {
        assertEquals(3, MoldContagion.next(4, 0));
        assertEquals(0, MoldContagion.next(0, 0));
    }
}
