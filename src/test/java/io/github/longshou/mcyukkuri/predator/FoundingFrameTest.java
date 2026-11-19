package io.github.longshou.mcyukkuri.predator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoundingFrameTest {
    private final FoundingFrame frame = new FoundingFrame("minecraft:overworld", 17, 5, 2624);

    @Test void aHomelessFounderChoosesItsFrameOverPatrolOrIdle() {
        assertTrue(FoundingFrame.chooseWork(frame.pressure(2624, true, false), 94, false));
        assertFalse(FoundingFrame.chooseWork(frame.pressure(2624, false, false), 94, false));
    }

    @Test void hungerCompetesWithTimeSpentWithoutANest() {
        assertFalse(FoundingFrame.chooseWork(frame.pressure(2624, true, false), 45, true));
        assertTrue(FoundingFrame.chooseWork(frame.pressure(3824, true, false), 45, true));
        assertFalse(FoundingFrame.chooseWork(frame.pressure(5024, true, true), 10, true));
    }

    @Test void theSameFrameYieldsToSleepAndWinsOnWaking() {
        assertFalse(FoundingFrame.chooseWork(frame.pressure(3352, true, true), 88, false));
        assertTrue(FoundingFrame.chooseWork(frame.pressure(4021, true, false), 85, false));
    }

    @Test void reloadedFieldsAndThePhysicalCountGiveTheSameDecision() {
        var restored = new FoundingFrame(frame.dimension(), frame.pos(), 3, frame.since()).observed(5);
        assertEquals(frame, restored);
        assertEquals(frame.pressure(4021, true, false), restored.pressure(4021, true, false));
        assertTrue(FoundingFrame.chooseWork(restored.pressure(4021, true, false), 85, true));
    }
}
