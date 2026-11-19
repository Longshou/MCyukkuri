package io.github.longshou.mcyukkuri.predator;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PerchScoreTest {
    @Test void heightAndViewFavourHighOpenBranches() {
        assertTrue(PerchScore.score(14, 4, false, false) > PerchScore.score(4, 4, false, false));
        assertTrue(PerchScore.score(4, 8, false, false) > PerchScore.score(4, 4, false, false));
        assertFalse(PerchScore.eligible(3, 8, 1, null, null));
        assertFalse(PerchScore.eligible(15, 8, 1, null, null));
        assertFalse(PerchScore.eligible(8, 3, 1, null, null));
        assertTrue(PerchScore.eligible(4, 4, 1, null, null));
        assertTrue(PerchScore.eligible(14, 4, 1, null, null));
    }
    @Test void shadeCountsOnlyAtMidday() {
        double open = PerchScore.score(7, 5, true, false);
        assertEquals(open, PerchScore.score(7, 5, false, true));
        assertEquals(open + .4, PerchScore.score(7, 5, true, true));
    }
    @Test void skipsBothRecentPerches() {
        assertFalse(PerchScore.eligible(6, 5, 1, 1L, 2L));
        assertFalse(PerchScore.eligible(6, 5, 2, 1L, 2L));
        assertTrue(PerchScore.eligible(6, 5, 3, 1L, 2L));
    }

    @Test void morningEveningAndRainKeepOrdinaryHunger() {
        assertFalse(PerchScore.midday(3499, true));
        assertTrue(PerchScore.midday(3500, true));
        assertTrue(PerchScore.midday(24000 + 8499, true));
        assertFalse(PerchScore.midday(8500, true));
        assertFalse(PerchScore.midday(6000, false));
        assertFalse(PerchScore.midday(18000, true));
        assertEquals(55, PerchScore.huntThreshold(54, false));
    }

    @Test void clearNoonShiftsHungerByBoldness() {
        assertEquals(45 - 18, PerchScore.huntThreshold(0, true));
        assertEquals(55 - 12, PerchScore.huntThreshold(50, true));
        assertEquals(65 - 6, PerchScore.huntThreshold(100, true));
    }
}
