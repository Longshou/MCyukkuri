package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.mind.Ousting;
import io.github.longshou.mcyukkuri.mind.Ousting.Answer;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OustingTest {
    @Test
    void temperIncludesTheBoundaryAndCockinessCanBeEnoughOnItsOwn() {
        var below = new Temperament(50, 70, 48, 50, 48);
        var at = new Temperament(50, 70, 50, 50, 50);
        var protester = new Temperament(50, 45, 48, 50, 49);
        assertEquals(.49, Ousting.temper(below));
        assertEquals(.50, Ousting.temper(at));
        assertFalse(below.is(Gate.PROTESTS_EXILE));
        assertFalse(at.is(Gate.PROTESTS_EXILE));
        assertFalse(Ousting.fumes(below));
        assertTrue(Ousting.fumes(at));
        assertTrue(Ousting.temper(protester) < .5);
        assertTrue(protester.is(Gate.PROTESTS_EXILE));
        assertEquals(Answer.CURSE, Ousting.answer(protester));
    }

    @Test
    void claimantsAndExilesAnswerByTemperament() {
        assertEquals(Answer.CURSE, Ousting.answer(new Temperament(50, 0, 100, 60, 0)));
        assertEquals(Answer.FAREWELL, Ousting.answer(new Temperament(50, 20, 45, 70, 50)));
        assertEquals(Answer.FAREWELL, Ousting.answer(new Temperament(50, 70, 30, 80, 70)));
        var aloof = new Temperament(50, 70, 40, 25, 65);
        assertTrue(aloof.is(Gate.ALOOF));
        assertFalse(Ousting.fumes(aloof));
        assertEquals(Answer.SHRUG, Ousting.answer(aloof));
    }

    @Test
    void wildProfilesKeepRoomForAllThreeAnswers() {
        var seeds = new Random(171119);
        int curses = 0, shrugs = 0;
        for (int n = 0; n < 10_000; n++) {
            long seed = seeds.nextLong();
            var t = new Temperament(TraitDraw.wild(seed, 0, 50), TraitDraw.wild(seed, 1, 50),
                    TraitDraw.wild(seed, 2, 50), TraitDraw.wild(seed, 3, 50), TraitDraw.wild(seed, 4, 50));
            switch (Ousting.answer(t)) {
                case CURSE -> curses++;
                case SHRUG -> shrugs++;
                case FAREWELL -> { }
            }
        }
        assertTrue(curses >= 4700 && curses <= 5400, "curses: " + curses);
        assertTrue(shrugs >= 500 && shrugs <= 1000, "shrugs: " + shrugs);
    }
}
