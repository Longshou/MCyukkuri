package io.github.longshou.mcyukkuri.family;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FarewellArbitrationTest {
    @Test void onlyTheAnswerCanReplaceHabitat() {
        assertTrue(replaces(FarewellArbitration.ANSWER_PRIORITY, 4, true));
        assertFalse(replaces(FarewellArbitration.DEPARTURE_PRIORITY, 4, true));
        assertTrue(replaces(FarewellArbitration.ANSWER_PRIORITY, 5, true));
        assertFalse(replaces(FarewellArbitration.ANSWER_PRIORITY, 5, false));
    }

    @Test void lifeNeedsAndDangerKeepTheirFlags() {
        assertFalse(replaces(FarewellArbitration.ANSWER_PRIORITY, 3, true));
        assertFalse(replaces(FarewellArbitration.ANSWER_PRIORITY, 2, true));
        assertFalse(FarewellArbitration.canAnswer(false, () -> false));
    }

    @Test void aDutyBlocksTheAnswerUntilItYields() {
        assertFalse(FarewellArbitration.canAnswer(true, () -> true));
        assertTrue(FarewellArbitration.canAnswer(true, () -> false));
    }

    // Vanilla WrappedGoal permits replacement only by a strictly higher-priority contender.
    private static boolean replaces(int contender, int owner, boolean interruptible) {
        return interruptible && contender < owner;
    }
}
