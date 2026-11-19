package io.github.longshou.mcyukkuri.predator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoopProgressTest {
    @Test void closingOnARunnerCanTakeMoreThanOneWindow() {
        var stoop = new StoopProgress();
        // WT-06 seed 3072587364960247267: measured contact-goal gaps through tick 61,
        // then the continuation at the same flight pace against the recorded prey track.
        double[] gaps = {6.0924, 4.4310, 3.7018, 2.9711, 2.1613, 1.1905};
        for (int i = 0; i < gaps.length; i++)
            assertFalse(stoop.stalled(1 + i * 15, gaps[i], .196));
        assertFalse(stoop.stalled(83, .5082, .196));
    }

    @Test void oscillatingOrFallingBehindCannotRenewAStoop() {
        var stoop = new StoopProgress();
        assertFalse(stoop.stalled(1, 5, .2));
        assertFalse(stoop.stalled(20, 4, .2));
        for (int tick = 21; tick < 80; tick++)
            assertFalse(stoop.stalled(tick, tick % 2 == 0 ? 3.9 : 4.4, .2));
        assertTrue(stoop.stalled(80, 4.4, .2));
        // A new pass measures its own approach even when it begins farther away.
        assertFalse(stoop.stalled(1, 7, .2));
        assertFalse(stoop.stalled(60, 7, .2));
        assertTrue(stoop.stalled(61, 7, .2));
    }
}
