package io.github.longshou.mcyukkuri.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContactPhaseTest {
    @Test void travelConsumesItsOwnBudget() {
        for (var phase : new ContactPhase[] {ContactPhase.APPROACH, ContactPhase.CLOSE}) {
            assertTrue(phase.canTick(9, 10));
            int elapsed = phase.elapsedAfterTick(9);
            assertEquals(10, elapsed);
            assertFalse(phase.canTick(elapsed, 10));
        }
    }

    @Test void clipsAndTheirHandoffsRunAtTheTravelDeadline() {
        for (var phase : new ContactPhase[] {ContactPhase.GREETING, ContactPhase.NUZZLE}) {
            assertTrue(phase.canTick(10, 10));
            assertEquals(10, phase.elapsedAfterTick(10));
        }
        int elapsed = ContactPhase.GREETING.elapsedAfterTick(9);
        assertTrue(ContactPhase.CLOSE.canTick(elapsed, 10));
        assertEquals(10, ContactPhase.CLOSE.elapsedAfterTick(elapsed));
    }
}
