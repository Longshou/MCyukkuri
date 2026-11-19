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
}
