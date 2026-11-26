package io.github.longshou.mcyukkuri.dialogue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpeechTimingTest {
    @Test void everyLineLeavesAGapThatGrowsWithItsLength() {
        var timing = new SpeechTiming();
        assertTrue(timing.admit(0, 100, 4, false));
        assertFalse(timing.admit(101, 40, 4, false), "next line right after the end waits");
        assertFalse(timing.admit(100 + 79, 40, 4, false));
        assertTrue(timing.admit(100 + 80, 40, 4, false), "gap = 30 + length / 2");
    }

    @Test void aFlooredPriorityDoesNotSkipTheGapOrCutALine() {
        var timing = new SpeechTiming();
        assertTrue(timing.admit(0, 127, 2, false));
        assertFalse(timing.admit(50, 40, 4, false), "a priority floor of 4 does not cut a line that is running");
        assertFalse(timing.admit(127 + 20, 40, 4, false), "nor enter the gap after it");
        assertTrue(timing.admit(127 + 94, 40, 4, false));
    }

    @Test void aPerformanceCueDoesNotTakeOverALineOfTheSamePriority() {
        var timing = new SpeechTiming();
        assertTrue(timing.admit(0, 100, 2, false));
        assertFalse(timing.admit(50, 40, 2, false));
    }

    @Test void anUrgentLineOfAHigherPriorityBreaksIn() {
        var running = new SpeechTiming();
        assertTrue(running.admit(0, 100, 2, false));
        assertTrue(running.admit(50, 40, 3, true), "a wound cuts the line");
        var gap = new SpeechTiming();
        assertTrue(gap.admit(0, 100, 2, false));
        assertTrue(gap.admit(101, 40, 3, true), "and enters the gap");
        assertFalse(gap.admit(101 + 10, 40, 3, true), "an urgent line of the same priority does not cut an urgent one");
    }

    @Test void chatterKeepsItsLongerQuietAgainstOrdinaryLines() {
        var timing = new SpeechTiming();
        assertTrue(timing.admit(0, 100, 0, false));
        assertFalse(timing.admit(100 + 199, 40, 1, false));
        assertTrue(timing.admit(100 + 200, 40, 1, false));
        var cue = new SpeechTiming();
        assertTrue(cue.admit(0, 100, 0, false));
        assertFalse(cue.admit(100 + 30, 40, 2, false), "a cue waits out the line's own gap too");
        assertTrue(cue.admit(100 + 80, 40, 2, false), "and is not held by the chatter quiet");
    }

    @Test void idleIsTheEndOfTheGap() {
        var timing = new SpeechTiming();
        timing.begin(0, 100, 2);
        assertFalse(timing.idle(100));
        assertFalse(timing.idle(179));
        assertTrue(timing.idle(180));
    }
}
