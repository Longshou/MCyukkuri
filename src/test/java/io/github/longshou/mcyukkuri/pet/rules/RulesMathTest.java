package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Discipline;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Feedback;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RulesMathTest {
    @Test void missingShiftUsesCurrentTemperament() {
        assertEquals(0, RulesMath.meekness(0, 0));
        assertEquals(100, RulesMath.meekness(1, 1));
        assertEquals(50, RulesMath.meekness(1, 0));
        assertEquals(50, RulesMath.meekness(0, 1));
        assertEquals(51, RulesMath.obedience(RulesMath.meekness(.51, .5), 0));
    }

    @Test void learnedShiftAndEffectiveObedienceHaveSeparateCaps() {
        assertEquals(-50, RulesMath.capShift(-53));
        assertEquals(50, RulesMath.capShift(54));
        assertEquals(0, RulesMath.obedience(10, -50));
        assertEquals(100, RulesMath.obedience(90, 50));
    }

    @Test void ownerDisciplineTeachesWithoutLosingCloseness() {
        assertEquals(new Discipline(0, 2), RulesMath.discipline(true, false, OptionalLong.empty()));
        assertEquals(new Discipline(0, 4), RulesMath.discipline(true, true, OptionalLong.empty()));
        assertEquals(new Discipline(-6, 0), RulesMath.discipline(false, false, OptionalLong.empty()));
        assertEquals(new Discipline(-20, 0), RulesMath.discipline(false, true, OptionalLong.of(0)));
    }

    @Test void onlyARecentBreachDoublesOwnerDiscipline() {
        assertEquals(4, RulesMath.discipline(true, false, OptionalLong.of(0)).learning());
        assertEquals(8, RulesMath.discipline(true, true, OptionalLong.of(1200)).learning());
        assertEquals(4, RulesMath.discipline(true, true, OptionalLong.of(1201)).learning());
    }

    @Test void earnedFeedbackTakesPrecedenceOverASnackDemand() {
        assertEquals(Feedback.REWARD, Feedback.sweet(true, true));
        assertEquals(1, Feedback.sweet(true, false).gain);
        assertEquals(Feedback.DEMAND, Feedback.sweet(false, true));
        assertEquals(-3, Feedback.DEMAND.gain);
        assertEquals(-3, Feedback.sweet(false, false).gain);
        assertEquals(-2, Feedback.FAVOR.gain);
    }
}
