package io.github.longshou.mcyukkuri.pet.rules;

import static io.github.longshou.mcyukkuri.pet.rules.StruggleOdds.Choice.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StruggleOddsTest {
    // attachment, boldness, closeness, fear, obedience, guilt, mobile
    @Test void aBoldAttachedParentStepsInFront() {
        assertEquals(SHIELD, StruggleOdds.score(90, 85, 60, 0, 30, .4, true).choice());
    }

    @Test void aTimidAttachedParentPleads() {
        assertEquals(PLEAD, StruggleOdds.score(90, 20, 60, 10, 40, .5, true).choice());
    }

    @Test void aDetachedObedientParentSubmits() {
        assertEquals(SUBMIT, StruggleOdds.score(20, 50, 0, 10, 90, .9, true).choice());
    }

    @Test void aDetachedFrightenedDisobedientParentRunsAndOnlyThen() {
        assertEquals(ESCAPE, StruggleOdds.score(15, 30, 0, 80, 20, .1, true).choice());
        // The same numbers in a parent that clings to the child do not run.
        assertNotEquals(ESCAPE, StruggleOdds.score(90, 30, 60, 80, 20, .1, true).choice());
    }

    @Test void aBodyThatCannotStepNeverShieldsOrRuns() {
        var weak = StruggleOdds.score(90, 85, 60, 0, 30, .4, false);
        assertEquals(PLEAD, weak.choice());
        assertEquals(0, weak.shield());
        assertEquals(0, StruggleOdds.score(15, 30, 0, 80, 20, .1, false).escape());
    }

    @Test void fearWeakensTheGuardNotTheOwner() {
        var calm = StruggleOdds.score(80, 60, 40, 0, 50, .5, true);
        var afraid = StruggleOdds.score(80, 60, 40, 60, 50, .5, true);
        assertTrue(afraid.guard() < calm.guard());
        assertEquals(calm.trust(), afraid.trust());
    }

    @Test void closeCallsHesitateLongerAndEveryScoreStaysInRange() {
        var even = StruggleOdds.score(60, 50, 30, 20, 60, .5, true);
        var lopsided = StruggleOdds.score(100, 100, 100, 0, 0, 0, true);
        assertTrue(even.struggleTicks() > lopsided.struggleTicks());
        for (var s : new StruggleOdds.Scores[] {even, lopsided})
            assertTrue(s.struggleTicks() >= 60 && s.struggleTicks() <= 120);
    }
}
