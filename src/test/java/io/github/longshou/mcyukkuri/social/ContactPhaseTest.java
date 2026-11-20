package io.github.longshou.mcyukkuri.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContactPhaseTest {
    @Test void anUnscheduledReceiverCannotHoldTheGreeterPastItsReplyDeadline() {
        long greeting = 2411, deadline = greeting + 200;
        for (long now = greeting; now < greeting + 2200; now++)
            assertEquals(now <= deadline, ContactPhase.answerOpen(now, deadline, false));
        assertTrue(ContactPhase.answerOpen(deadline + 1, deadline, true));
    }

    @Test void travelAllowanceUsesTheGapAndTheWalkingBody() {
        assertEquals(0, ContactPhase.walkTicks(.5, .75, .1));
        assertEquals(8, ContactPhase.walkTicks(1.5, .75, .1));
        assertEquals(15, ContactPhase.walkTicks(1.5, .75, .05));
    }

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

    @Test void aLongApproachLeavesAllLevelRubFramesAvailable() {
        int approach = ContactPhase.walkTicks(12, 0, .04);
        var rub = new PairContactClock(140);
        assertTrue(ContactPhase.APPROACH.canTick(approach - 1, approach));
        assertFalse(ContactPhase.APPROACH.canTick(approach, approach));
        for (int frame = 0; frame < 140; frame++) {
            assertTrue(ContactPhase.NUZZLE.canTick(approach, approach));
            rub.publish(approach + frame, ignored -> {}, ignored -> {});
        }
        assertTrue(rub.finished(approach + 140));
        assertEquals(140, rub.releasedTicks(approach + 140));
    }
}
