package io.github.longshou.mcyukkuri.pet.rules;

import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.pet.rules.RulesMath.*;
import static org.junit.jupiter.api.Assertions.*;

class TemptationTest {
    @Test void pressureCompetesWithPatienceAndObedience() {
        assertEquals(.80, hold(.5, 50, .5), .0001);
        assertEquals(.99, hold(1, 100, .5), .0001);
        assertEquals(.50, hold(0, 0, .5), .0001);
        assertEquals(.60, hold(.5, 50, 1), .0001);
        assertEquals(Choice.HOLD, choose(.7, .8, .7, true, 1));
        assertEquals(Choice.WAIT, choose(.9, .8, .7, true, 1));
        assertEquals(Choice.BREAK, choose(.9, .8, .7, false, 1));
        assertEquals(Choice.BREAK, choose(.9, .8, .3, true, 1));
    }

    @Test void acuteHungerEndsEvenAWatchedCleverWait() {
        assertEquals(0, starvingGuard(10));
        assertEquals(.5, starvingGuard(20));
        assertEquals(1, starvingGuard(30));
        assertEquals(Choice.BREAK, choose(0, .99, 1, true, starvingGuard(9)));
        assertEquals(Choice.BREAK, choose(.9, .99, .8, true, starvingGuard(20)));
        assertTrue(servingWait(100, 2) > servingWait(100, 0));
        assertEquals(80, servingWait(220, 3));
    }

    @Test void onlyACompletedUnauthorizedActionIsABreach() {
        for (var choice : Choice.values()) assertFalse(choice.breached(false));
        assertTrue(Choice.BREAK.breached(true));
        assertFalse(Choice.ALLOW.breached(true));
        assertFalse(Choice.HOLD.breached(true));
        assertFalse(Choice.WAIT.breached(true));
    }
}
