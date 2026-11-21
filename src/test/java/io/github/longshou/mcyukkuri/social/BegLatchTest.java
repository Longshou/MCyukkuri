package io.github.longshou.mcyukkuri.social;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BegLatchTest {
    @Test void aFreshRoundAllowsAndTheSameConditionNeverRepeats() {
        var latch = new BegLatch();
        assertTrue(latch.allows(0, 60, 7));
        latch.ended(100, 60, 7);
        assertFalse(latch.allows(100, 60, 7));
        assertFalse(latch.allows(100 + 400, 60, 7));      // cooldown over, nothing changed
        assertFalse(latch.allows(100 + 399, 49, 7));      // hungrier, but cooldown not over
        assertTrue(latch.allows(100 + 400, 50, 7));       // hungrier by 10
        assertTrue(latch.allows(100 + 400, 60, 8));       // another pane
    }

    @Test void cooldownDoublesAndTheRoundEndsAfterThree() {
        var latch = new BegLatch();
        latch.ended(0, 60, 1);
        assertFalse(latch.allows(399, 20, 2));
        assertTrue(latch.allows(400, 20, 2));
        latch.ended(400, 60, 2);
        assertFalse(latch.allows(400 + 799, 20, 3));
        assertTrue(latch.allows(400 + 800, 20, 3));
        latch.ended(1200, 60, 3);
        assertEquals(3, latch.count());
        assertFalse(latch.allows(1200 + 100_000, 0, 4));
    }

    @Test void aMealStartsANewRound() {
        var latch = new BegLatch();
        latch.ended(0, 60, 1); latch.ended(400, 50, 1); latch.ended(1200, 40, 1);
        assertFalse(latch.allows(5000, 10, 9));
        latch.fed(54);
        assertFalse(latch.allows(5000, 10, 9));
        latch.fed(55);
        assertTrue(latch.allows(5000, 60, 1));
    }
}
