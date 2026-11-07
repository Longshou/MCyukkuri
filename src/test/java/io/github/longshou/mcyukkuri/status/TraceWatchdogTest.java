package io.github.longshou.mcyukkuri.status;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TraceWatchdogTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void fakeClockReportsOneStallAndRearmsOnlyAfterATick() {
        var clock = new TraceWatchdog.StallTracker(5 * SECOND);
        assertEquals(0, clock.poll(10 * SECOND, 0), "a recorder with no completed tick waits for its first timestamp");
        assertEquals(0, clock.poll(10 * SECOND, 8 * SECOND));
        assertEquals(0, clock.poll(12_999_999_999L, 8 * SECOND));
        assertEquals(5 * SECOND, clock.poll(13 * SECOND, 8 * SECOND), "the threshold is inclusive");
        assertEquals(0, clock.poll(30 * SECOND, 8 * SECOND), "one continuous stall produces one line");
        assertEquals(0, clock.poll(30 * SECOND, 29 * SECOND), "a completed tick rearms the detector");
        assertEquals(6 * SECOND, clock.poll(35 * SECOND, 29 * SECOND), "a later stall is a new incident");
    }

    @Test
    void changingTickTimestampDoesNotInheritTheOldStall() {
        var clock = new TraceWatchdog.StallTracker(5 * SECOND);
        assertEquals(5 * SECOND, clock.poll(20 * SECOND, 15 * SECOND));
        assertEquals(0, clock.poll(21 * SECOND, 20 * SECOND));
        assertEquals(0, clock.poll(24 * SECOND, 20 * SECOND));
        assertEquals(5 * SECOND, clock.poll(25 * SECOND, 20 * SECOND));
    }
}
