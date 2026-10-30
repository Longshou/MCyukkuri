package io.github.longshou.mcyukkuri.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The pure scoring and switching rule; no Minecraft type is touched here. */
class DriveMathTest {

    @Test
    void scoreClampsDesireAndFloorsWeightAndCost() {
        assertEquals(190, DriveMath.score(150, 2, 10), 1E-9);
        assertEquals(0, DriveMath.score(-5, 1, 0), 1E-9);
        assertEquals(0, DriveMath.score(50, 0, 0), 1E-9);
        assertEquals(40, DriveMath.score(50, 1, 10), 1E-9);
        assertEquals(0, DriveMath.score(Double.NaN, 1, 0), 1E-9);
    }

    @Test
    void ordinarySwitchNeedsFifteenPercentPlusFive() {
        assertTrue(DriveMath.switchable(120, 100, Commitment.NONE, false));
        assertFalse(DriveMath.switchable(119.9, 100, Commitment.NONE, false));
        assertTrue(DriveMath.switchable(5, 0, Commitment.NONE, false));
        assertFalse(DriveMath.switchable(4.9, 0, Commitment.NONE, false));
    }

    @Test
    void softCommitmentNeedsTwiceTheScore() {
        assertTrue(DriveMath.switchable(205, 100, Commitment.SOFT, false));
        assertFalse(DriveMath.switchable(204.9, 100, Commitment.SOFT, false));
    }

    @Test
    void hardCommitmentOnlyYieldsToDanger() {
        assertFalse(DriveMath.switchable(1_000, 100, Commitment.HARD, false));
        assertTrue(DriveMath.switchable(0, 100, Commitment.HARD, true));
    }

    @Test
    void dangerPreemptsOnTheEvaluationThatNoticesIt() {
        assertFalse(DriveMath.needsSustain(true));
        assertTrue(DriveMath.needsSustain(false));
        // SOFT drops the +5 slack for a danger desire but still must outscore the held intent.
        assertTrue(DriveMath.switchable(100, 100, Commitment.SOFT, true));
        assertFalse(DriveMath.switchable(99.9, 100, Commitment.SOFT, true));
    }

    @Test
    void sustainNeedsTenConsecutiveEvaluations() {
        DriveMath.Sustain sustain = new DriveMath.Sustain();
        for (int i = 1; i < DriveMath.SUSTAIN_EVALUATIONS; i++) {
            assertFalse(sustain.observe("rest", true), "evaluation " + i + " must not switch yet");
            assertEquals(i, sustain.streak());
        }
        assertTrue(sustain.observe("rest", true));
        assertTrue(sustain.observe("rest", true), "a sustained switch stays allowed");
        sustain.observe("rest", false);
        assertEquals(0, sustain.streak());
        assertFalse(sustain.observe("rest", true), "a failed evaluation restarts the count");
    }

    @Test
    void aDifferentCandidateRestartsTheSustain() {
        DriveMath.Sustain sustain = new DriveMath.Sustain();
        for (int i = 1; i < DriveMath.SUSTAIN_EVALUATIONS; i++) sustain.observe("rest", true);
        assertEquals(9, sustain.streak());
        assertFalse(sustain.observe("eat", true));
        assertEquals(1, sustain.streak());
        assertEquals("eat", sustain.candidate());
        sustain.reset();
        assertEquals(0, sustain.streak());
        assertFalse(sustain.observe("eat", true));
    }

    @Test
    void evaluationIsStaggeredByEntityIdModuloTen() {
        assertTrue(DriveMath.due(0, 0));
        assertTrue(DriveMath.due(10, 0));
        assertFalse(DriveMath.due(5, 0));
        assertTrue(DriveMath.due(5, 5));
        assertTrue(DriveMath.due(15, 5));
        assertFalse(DriveMath.due(10, 5));
        // Before tick 0 the modulo must still line up, not go negative.
        assertTrue(DriveMath.due(-5, 5));
        assertFalse(DriveMath.due(-5, 0));
    }
}
