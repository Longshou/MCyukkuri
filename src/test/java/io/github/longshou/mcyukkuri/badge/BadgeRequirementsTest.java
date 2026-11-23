package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.badge.BadgeRequirements.Candidate;
import org.junit.jupiter.api.Test;

import static io.github.longshou.mcyukkuri.badge.BadgeRequirements.Need.*;
import static io.github.longshou.mcyukkuri.badge.BadgeRequirements.firstMissing;
import static org.junit.jupiter.api.Assertions.*;

class BadgeRequirementsTest {
    @Test
    void bronzeHasNoSkillRequirement() {
        assertNull(firstMissing(1, new Candidate(0, 0, 0, 0, 0, 0, false, false, 0, true)));
    }

    @Test
    void everyGradeFromBronzeNeedsAKindlyNature() {
        var selfish = new Candidate(3, 3, 3, 3, 3, 8, true, true, 90, false);
        for (int grade = 1; grade <= 4; grade++) assertEquals(KINDLY, firstMissing(grade, selfish));
        assertNull(firstMissing(4, new Candidate(3, 3, 3, 3, 3, 8, true, true, 90, true)));
    }

    @Test
    void silverRequiresItsRowInOrder() {
        assertEquals(WAIT_2, firstMissing(2, new Candidate(1, 0, 0, 0, 0, 2, false, false, 0, true)));
        assertEquals(TOILET_2, firstMissing(2, new Candidate(2, 1, 0, 0, 0, 2, false, false, 0, true)));
        assertEquals(GREETING_1, firstMissing(2, new Candidate(2, 2, 0, 0, 0, 2, false, false, 0, true)));
        assertEquals(COUNT_3, firstMissing(2, new Candidate(2, 2, 0, 0, 1, 2, false, false, 0, true)));
        assertNull(firstMissing(2, new Candidate(2, 2, 0, 0, 1, 3, false, false, 0, true)));
    }

    @Test
    void goldRequiresSilverAndItsOwnRow() {
        assertEquals(COUNT_3, firstMissing(3, new Candidate(2, 2, 1, 0, 1, 2, false, false, 0, true)));
        assertEquals(FOOD_GUARD_2, firstMissing(3, new Candidate(2, 2, 1, 0, 1, 3, false, false, 0, true)));
        assertEquals(MANNERS_2, firstMissing(3, new Candidate(2, 2, 2, 1, 1, 3, false, false, 0, true)));
        assertEquals(GREETING_2, firstMissing(3, new Candidate(2, 2, 2, 2, 1, 3, false, false, 0, true)));
        assertEquals(COUNT_5, firstMissing(3, new Candidate(2, 2, 2, 2, 2, 4, false, false, 0, true)));
        assertEquals(HELPFUL, firstMissing(3, new Candidate(2, 2, 2, 2, 2, 5, false, false, 0, true)));
        assertNull(firstMissing(3, new Candidate(2, 2, 2, 2, 2, 5, true, false, 0, true)));
    }

    @Test
    void platinumRequiresGoldAndItsOwnRow() {
        assertEquals(HELPFUL, firstMissing(4, new Candidate(3, 3, 3, 3, 3, 8, false, true, 85, true)));
        assertEquals(WAIT_3, firstMissing(4, new Candidate(2, 2, 2, 2, 2, 5, true, false, 0, true)));
        assertEquals(TOILET_3, firstMissing(4, new Candidate(3, 2, 2, 2, 2, 5, true, false, 0, true)));
        assertEquals(FOOD_GUARD_3, firstMissing(4, new Candidate(3, 3, 2, 2, 2, 5, true, false, 0, true)));
        assertEquals(MANNERS_3, firstMissing(4, new Candidate(3, 3, 3, 2, 2, 5, true, false, 0, true)));
        assertEquals(GREETING_3, firstMissing(4, new Candidate(3, 3, 3, 3, 2, 5, true, false, 0, true)));
        assertEquals(COUNT_8, firstMissing(4, new Candidate(3, 3, 3, 3, 3, 7, true, false, 0, true)));
        assertEquals(CONSIDERATE, firstMissing(4, new Candidate(3, 3, 3, 3, 3, 8, true, false, 0, true)));
        assertEquals(TALENT_85, firstMissing(4, new Candidate(3, 3, 3, 3, 3, 8, true, true, 84, true)));
        assertNull(firstMissing(4, new Candidate(3, 3, 3, 3, 3, 8, true, true, 85, true)));
    }

    @Test
    void talentRoundsTheMeanOfTheFourPotentials() {
        assertEquals(84, BadgeRequirements.talent(75, 80, 90, 92));
        assertEquals(85, BadgeRequirements.talent(75, 80, 90, 93));
    }
}
