package io.github.longshou.mcyukkuri.dialogue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpeechTimingTest {
    @Test void aPerformanceLineLeavesAGapThatGrowsWithItsLength() {
        var timing = new SpeechTiming();
        assertTrue(timing.admit(0, 100, 4));
        assertFalse(timing.admit(101, 40, 4), "next line of the same priority right after the end waits");
        assertFalse(timing.admit(100 + 79, 40, 4));
        assertTrue(timing.admit(100 + 80, 40, 4), "gap = 30 + length / 2");
    }

    @Test void aHigherPriorityLineIsNotHeldByTheGap() {
        var timing = new SpeechTiming();
        assertTrue(timing.admit(0, 100, 2));
        assertTrue(timing.admit(101, 40, 3));
    }
}
