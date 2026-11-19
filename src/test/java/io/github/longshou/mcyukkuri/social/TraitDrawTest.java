package io.github.longshou.mcyukkuri.social;

import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraitDrawTest {
    private static final int SAMPLES = 10_000;

    @Test
    void eachWildAxisHasTheExpectedSpread() {
        for (int axis = 0; axis < 5; axis++) {
            Random seeds = new Random(171119);
            double total = 0;
            double squares = 0;
            int low = 0;
            for (int n = 0; n < SAMPLES; n++) {
                long seed = seeds.nextLong();
                int value = TraitDraw.wild(seed, axis, TraitDraw.WILD_CENTRE);
                assertEquals(value, TraitDraw.wild(seed, axis, TraitDraw.WILD_CENTRE));
                assertTrue(value >= 10 && value <= 90);
                total += value;
                squares += value * value;
                if (value <= 35) low++;
            }
            double mean = total / SAMPLES;
            double sd = Math.sqrt(squares / SAMPLES - mean * mean);
            assertEquals(50, mean, .6);
            assertTrue(sd >= 14.5 && sd <= 15.5, "sd=" + sd);
            assertTrue(low >= 1400 && low <= 1900, "low=" + low);
        }
    }

    @Test
    void inheritanceVariesTheParentalMeanWithinTheChildForm() {
        Random seeds = new Random(171119);
        double knownTotal = 0;
        double unknownTotal = 0;
        for (int n = 0; n < SAMPLES; n++) {
            long seed = seeds.nextLong();
            int child = TraitDraw.inherit(seed, 0, 30, 70, 0, 100);
            assertEquals(child, TraitDraw.inherit(seed, 0, 30, 70, 0, 100));
            assertTrue(child >= 35 && child <= 65);
            knownTotal += child;
            int unknown = TraitDraw.unknownParent(seed, 1);
            unknownTotal += TraitDraw.inherit(seed, 1, 80, unknown, 0, 100);
            int deficient = TraitDraw.inherit(seed, 0, 30, 70, 0, 25);
            assertTrue(deficient >= 0 && deficient <= 25);
        }
        assertEquals(50, knownTotal / SAMPLES, 1);
        assertEquals(65, unknownTotal / SAMPLES, 1.5);
    }

    @Test
    void halfPointsRoundUpInOrdinaryAndNarrowForms() {
        assertEquals(15, TraitDraw.inForm(58, 0, 25));
        assertEquals(90, TraitDraw.inForm(58, 75, 100));
        for (long seed = 0; seed < 100; seed++) {
            int noise = TraitDraw.inherit(seed, 0, 50, 50, 0, 100) - 50;
            assertEquals(25 + noise, TraitDraw.inherit(seed, 0, 19, 30, 0, 100));
        }
    }
}
