package io.github.longshou.mcyukkuri.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FailedRetreatTest {
    @Test void persistentThreatRetriesOnceEveryTenSeconds() {
        var failure = new FailedRetreat<Double>();
        var pursuer = new UUID(0, 1);
        int searches = 0;
        for (int tick = 0; tick < 2400; tick++) {
            if (!failure.stillApplies(tick, null, null) || !failure.holds(pursuer, 1.25, 3.5, null)) {
                assertEquals(0, tick % 200);
                searches++;
                failure.remember(pursuer, 1.25, 3.5, null, tick, null, null);
            }
            assertTrue(failure.cornered(pursuer, 1.25, 3.5));
        }
        assertEquals(12, searches);
        assertFalse(failure.holds(pursuer, 1.26, 3.5, null));
        assertFalse(failure.holds(pursuer, 1.25, 3.51, null));
        assertFalse(failure.holds(new UUID(0, 2), 1.25, 3.5, null));
        failure.clear(); // Collision change in the search area, with both actors still in place.
        assertFalse(failure.holds(pursuer, 1.25, 3.5, null));
    }

    @Test void keeperAndRefugeChangesReleaseTheHoldBeforeExpiry() {
        var failure = new FailedRetreat<Integer>();
        var pursuer = new UUID(0, 1);
        var keeper = new UUID(0, 2);
        failure.remember(pursuer, 2, 1, null, 140, null, 12);
        assertTrue(failure.stillApplies(339, null, 12));
        assertFalse(failure.stillApplies(340, null, 12));
        assertFalse(failure.stillApplies(141, keeper, 12));
        assertFalse(failure.stillApplies(141, null, 13));
        failure.remember(pursuer, 2, 1, null, 141, keeper, 13);
        assertTrue(failure.stillApplies(142, keeper, 13));
        assertFalse(failure.stillApplies(142, null, 13));
    }

    @Test void sourcelessPanicSearchesAgainAfterOneSecond() {
        var failure = new FailedRetreat<Integer>();
        failure.remember(null, 2, null, null, 100, null, null, FailedRetreat.SOURCELESS_RETRY_TICKS);
        assertTrue(failure.stillApplies(119, null, null));
        assertFalse(failure.stillApplies(120, null, null));
    }

    @Test void evictionKeepsItsDangerAndAnchorSeparateFromSourcelessPanic() {
        var failure = new FailedRetreat<Integer>();
        failure.remember(null, 2, 1, 16, 100, null, null);
        assertTrue(failure.cornered(null, 2, null));
        assertTrue(failure.holds(null, 2, 1, 16));
        assertFalse(failure.holds(null, 2, 0, 16));
        assertFalse(failure.holds(null, 2, 1, 18));
        assertFalse(failure.holds(null, 2, null, null));
        failure.remember(null, 2, null, null, 100, null, null);
        assertTrue(failure.holds(null, 2, null, null));
    }
}
