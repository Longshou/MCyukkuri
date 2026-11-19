package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.TraitBounds;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpbringingTest {
    private static final Upbringing BIRTH = new Upbringing(0, 0);
    private static final TraitBounds ORDINARY = TraitBounds.forForm(false, Kind.NORMAL);

    private Upbringing event(Upbringing state, int units, boolean young) {
        return state.after(units, young, 50, Trait.SELF_CONTROL, ORDINARY);
    }

    @Test
    void fourAdultOrTwoYoungExperiencesEarnOnePointInEitherDirection() {
        for (int sign : new int[] {-1, 1}) {
            var adult = BIRTH;
            for (int n = 1; n <= 3; n++) {
                adult = event(adult, sign, false);
                assertEquals(new Upbringing(0, n * sign), adult);
            }
            var point = new Upbringing(sign, 0);
            assertEquals(point, event(adult, sign, false));
            assertEquals(point, event(event(BIRTH, sign, true), sign, true));
        }
    }

    @Test
    void oppositeEventsCancelAndWeightedEventsCanCrossSeveralPoints() {
        assertEquals(BIRTH, event(event(BIRTH, -1, true), 2, false));
        assertEquals(new Upbringing(-2, -1), event(new Upbringing(0, -3), -3, true));
    }

    @Test
    void earnedCapsDiscardOutwardUnitsAndKeepTheFirstReversal() {
        for (int sign : new int[] {-1, 1}) {
            var capped = new Upbringing(sign * 25, 0);
            assertEquals(capped, event(new Upbringing(sign * 24, sign * 3), sign, true));
            assertEquals(capped, event(capped, sign, false));
            assertEquals(new Upbringing(sign * 25, -sign), event(capped, -sign, false));
        }
    }

    @Test
    void changedFormsAndOldSavedRemaindersAreBoundedWithoutEarningPoints() {
        for (int sign : new int[] {-1, 1}) {
            assertEquals(new Upbringing(sign * 16, sign * 3),
                    Upbringing.bounded(sign * 16, sign * 7, 50, Trait.BOLDNESS, ORDINARY));
        }
        var deficient = TraitBounds.forForm(true, Kind.NORMAL);
        var wasa = TraitBounds.forForm(false, Kind.WASA_REIMU);
        assertFormCap(deficient, Trait.INTELLIGENCE, 23, 2);
        assertFormCap(deficient, Trait.FRIENDLINESS, 80, -5);
        assertFormCap(wasa, Trait.ATTACHMENT, 80, -5);
        assertFormCap(wasa, Trait.SELF_CONTROL, 23, 2);
        assertFormCap(wasa, Trait.FRIENDLINESS, 23, 2);
    }

    private void assertFormCap(TraitBounds form, Trait trait, int innate, int limit) {
        int sign = Integer.signum(limit);
        var capped = Upbringing.bounded(sign * 20, sign * 7, innate, trait, form);
        assertEquals(new Upbringing(limit, 0), capped);
        assertEquals(capped, capped.after(sign, false, innate, trait, form));
        assertEquals(new Upbringing(limit, -sign), capped.after(-sign, false, innate, trait, form));
    }
}
