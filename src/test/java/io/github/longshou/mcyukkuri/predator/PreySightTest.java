package io.github.longshou.mcyukkuri.predator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PreySightTest {
    @Test
    void gradesAreQuartersOfTheScore() {
        assertEquals(0, PreySight.gradeOf(0));
        assertEquals(0, PreySight.gradeOf(.24));
        assertEquals(1, PreySight.gradeOf(.25));
        assertEquals(2, PreySight.gradeOf(.5));
        assertEquals(3, PreySight.gradeOf(.75));
        assertEquals(3, PreySight.gradeOf(1.4));
    }

    @Test
    void scoresGrowWithTheirTraitsAndAge() {
        assertTrue(PreySight.huntScore(80, 50, true) > PreySight.huntScore(20, 50, true));
        assertTrue(PreySight.huntScore(50, 50, true) > PreySight.huntScore(50, 50, false));
        assertTrue(PreySight.hidingScore(80, 50, false) > PreySight.hidingScore(20, 50, false));
    }

    @Test
    void coveredDetectRangeRunsFourToSixteenBlocksByHunterGrade() {
        assertEquals(4, PreySight.detectRange(0, 0, false), 1e-9);
        assertEquals(16, PreySight.detectRange(3, 0, false), 1e-9);
        assertEquals(24, PreySight.detectRange(3, 0, true), 1e-9);
    }

    @Test
    void preyCraftShortensTheRangeAndMovementLengthensIt() {
        assertTrue(PreySight.detectRange(2, 3, false) < PreySight.detectRange(2, 0, false));
        assertEquals(.55, PreySight.detectRange(2, 3, false) / PreySight.detectRange(2, 0, false), 1e-9);
        assertTrue(PreySight.detectRange(2, 1, true) > PreySight.detectRange(2, 1, false));
    }

    @Test
    void openPreyIsAlwaysSeenAndCoveredPreyOnlyInsideItsRange() {
        assertTrue(PreySight.seen(59, false, false, 0, 3));
        assertFalse(PreySight.seen(30, true, false, 3, 0));
        assertTrue(PreySight.seen(16, true, false, 3, 0));
        assertFalse(PreySight.seen(16.1, true, false, 3, 0));
        assertFalse(PreySight.seen(9, true, false, 2, 3));
        assertTrue(PreySight.seen(9, true, true, 2, 3));
    }
}
