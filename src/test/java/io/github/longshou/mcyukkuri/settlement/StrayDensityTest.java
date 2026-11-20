package io.github.longshou.mcyukkuri.settlement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrayDensityTest {
    @Test void inhabitedBedsScaleArrivalsAndBadWeatherClosesVacancies() {
        for (int beds : new int[] {0, 1, 3, 4, 8, 16, 100}) {
            int fair = switch (beds) {
                case 0 -> 0;
                case 1, 3 -> 2;
                case 4 -> 3;
                case 8 -> 4;
                default -> 6;
            };
            assertEquals(fair, StrayDensity.target(beds, false, false));
            int floor = beds == 0 ? 0 : 1;
            assertEquals(Math.max(floor, fair - 1), StrayDensity.target(beds, true, false));
            assertEquals(Math.max(floor, fair - 1), StrayDensity.target(beds, false, true));
            assertEquals(Math.max(floor, fair - 2), StrayDensity.target(beds, true, true));
        }
    }

    @Test void conversionUsesTheExistingBodyAndBothCapsStillApply() {
        assertTrue(StrayDensity.admits(1, 7, 7, 8, 12, false));
        assertTrue(StrayDensity.admits(1, 8, 8, 8, 12, true));
        assertFalse(StrayDensity.admits(1, 8, 7, 8, 12, false));
        assertFalse(StrayDensity.admits(1, 6, 8, 8, 12, false));
        assertFalse(StrayDensity.admits(1, 5, 6, 8, 6, false));
        assertFalse(StrayDensity.admits(1, 9, 7, 8, 12, true));
        assertFalse(StrayDensity.admits(1, 7, 9, 8, 12, true));
        assertFalse(StrayDensity.admits(0, 1, 1, 8, 12, true));
        assertFalse(StrayDensity.admits(-1, 1, 1, 8, 12, false));
    }
}
