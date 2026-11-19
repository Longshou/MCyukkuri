package io.github.longshou.mcyukkuri.cognition;

import org.junit.jupiter.api.Test;

import static io.github.longshou.mcyukkuri.cognition.SkillMath.Attempt.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillMathTest {
    @Test
    void gradesUsePracticeThresholds() {
        int[] points = {0, 1, 2, 4, 5, 8, 9};
        int[] grades = {0, 0, 1, 1, 2, 2, 3};
        for (int i = 0; i < points.length; i++) {
            assertEquals(grades[i], SkillMath.grade(points[i], true));
            assertEquals(Math.min(grades[i], 1), SkillMath.grade(points[i], false));
        }
        for (int grade = 0; grade <= 3; grade++) {
            assertEquals(grade, SkillMath.grade(SkillMath.pointsForGrade(grade, true), true));
        }
        assertEquals(2, SkillMath.pointsForGrade(1, false));
    }

    @Test
    void competenceAndWillingnessPartitionAttemptsAtTheirEdges() {
        double[] can = {.15, .55, .80, .95};
        for (int grade = 0; grade < can.length; grade++) {
            assertEquals(CLUMSY, SkillMath.attempt(grade, .5, can[grade], 0));
            assertEquals(DEFIED, SkillMath.attempt(grade, .5, Math.nextDown(can[grade]), .5));
            assertEquals(DONE, SkillMath.attempt(grade, .5, Math.nextDown(can[grade]), Math.nextDown(.5)));
        }
    }

    @Test
    void willingnessRespondsToTraitsAndClamps() {
        assertEquals(.10, SkillMath.will(0, 100, 100, 0));
        assertEquals(1, SkillMath.will(1, 0, 0, 1), 1e-9);
        assertEquals(.60, SkillMath.will(.75, 20, 40, .75), 1e-9);
    }

    @Test
    void countingBaseAndCeilingFollowIntelligence() {
        int[] intelligence = {0, 34, 35, 64, 65, 90, 100};
        int[] bases = {1, 1, 2, 2, 3, 3, 3};
        int[] ceilings = {3, 5, 5, 7, 7, 9, 9};
        for (int i = 0; i < intelligence.length; i++) {
            assertEquals(bases[i], SkillMath.capBase(intelligence[i]));
            assertEquals(ceilings[i], SkillMath.capCeiling(intelligence[i]));
        }
    }
}
