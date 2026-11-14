package io.github.longshou.mcyukkuri.life;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TasteTest {
    @Test void easyTasteSavoursCoarseFood() {
        assertEquals(Taste.Verdict.SAVOUR, Taste.judge(12, Taste.Grade.COARSE, 48));
    }

    @Test void highTasteRefusesOrdinaryFoodWhenNotHungry() {
        assertEquals(Taste.Verdict.REFUSE, Taste.judge(95, Taste.Grade.ORDINARY, 55));
    }

    @Test void highTasteEatsOrdinaryFoodGrudginglyWhenHungryEnough() {
        assertEquals(Taste.Verdict.GRUMBLE, Taste.judge(95, Taste.Grade.ORDINARY, 35));
    }

    @Test void starvationAcceptsOrdinaryFoodAtAnyTaste() {
        assertEquals(Taste.Verdict.PLAIN, Taste.judge(100, Taste.Grade.ORDINARY, 25));
    }

    @Test void starvationAcceptsCoarseFoodAtAnyTaste() {
        assertEquals(Taste.Verdict.GRUMBLE, Taste.judge(100, Taste.Grade.COARSE, 25));
        assertEquals(Taste.Verdict.GRUMBLE, Taste.judge(100, Taste.Grade.COARSE, 10));
        assertEquals(Taste.Verdict.SAVOUR, Taste.judge(20, Taste.Grade.COARSE, 25));
    }

    @Test void fineAndTreatFoodAreNeverRefusedForTaste() {
        for (float nutrition = 0; nutrition <= 100; nutrition += 5) {
            assertTrue(Taste.judge(100, Taste.Grade.FINE, nutrition) != Taste.Verdict.REFUSE);
            assertTrue(Taste.judge(100, Taste.Grade.TREAT, nutrition) != Taste.Verdict.REFUSE);
        }
    }

    @Test void swallowedMealsMoveTasteByGradeAndHunger() {
        assertEquals(2, Taste.afterMeal(Taste.Grade.TREAT, 60));
        assertEquals(1, Taste.afterMeal(Taste.Grade.FINE, 60));
        assertEquals(0, Taste.afterMeal(Taste.Grade.ORDINARY, 60));
        assertEquals(-2, Taste.afterMeal(Taste.Grade.COARSE, 60));
        assertEquals(-3, Taste.afterMeal(Taste.Grade.COARSE, 20));
        assertEquals(0, Taste.afterMeal(Taste.Grade.FINE, 20));
    }

    @Test void innateSpreadStaysBetweenThirtyAndSeventy() {
        for (int i = 0; i < 1000; i++) {
            int value = Taste.innate(new UUID(i * 31L, i * 7919L));
            assertTrue(value >= 30 && value <= 70);
        }
    }

    @Test void effectiveTasteReadsEsteemAndFeeder() {
        assertEquals(60, Taste.effective(60, 50, 0));
        assertEquals(62, Taste.effective(60, 55, 0));
        assertEquals(52, Taste.effective(60, 50, 10));
        assertEquals(66, Taste.effective(60, 50, -1));
        assertEquals(100, Taste.effective(100, 100, 0));
        assertEquals(0, Taste.effective(0, 0, 0));
    }
}
