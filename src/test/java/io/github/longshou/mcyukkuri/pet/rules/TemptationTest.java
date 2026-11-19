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
}
