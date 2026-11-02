package io.github.longshou.mcyukkuri.aquatic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoyageReadinessTest {
    private static double desire(double ratio, double boldness) {
        return VoyageReadiness.desire(ratio, boldness, .80, .95, .05, 95);
    }

    @Test void belowAndAtTheBandStartHaveNoDesire() {
        assertEquals(0, desire(.79, 50));
        assertEquals(0, desire(.80, 50));
    }

    @Test void desireRampsLinearlyInsideTheBand() {
        assertEquals(47.5, desire(.875, 50), 1e-9);
    }

    @Test void desireStaysStrongAtAndAboveTheBandEnd() {
        assertEquals(95, desire(.95, 50));
        assertEquals(95, desire(1, 50));
        assertEquals(95, desire(1.2, 50));
    }

    @Test void boldnessShiftsTheBandEarlierAndTimidityLater() {
        assertEquals(47.5, desire(.825, 100), 1e-9);
        assertEquals(47.5, desire(.925, 0), 1e-9);
        assertEquals(0, desire(.85, 0));
        assertEquals(95, desire(.90, 100));
    }
}
