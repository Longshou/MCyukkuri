package io.github.longshou.mcyukkuri.family;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FamilyDutyMathTest {
    private static final double EPSILON = 1e-9;

    @Test
    void statRangesAndThreePersonalities() {
        check(0, 0, 0, 0, 2, 60, 120, 3, 50, 2, 20, 200);
        check(100, 100, 100, 100, 3.5, 200, 60, 1, 30, 6, 60, 700);
        assertEquals(5, FamilyDutyMath.patrolRadius(100, 0), EPSILON);
        assertEquals(.5, FamilyDutyMath.watchStart(0), EPSILON);
        assertEquals(.2, FamilyDutyMath.watchStart(100), EPSILON);
        check(20, 90, 80, 60, 2.33, 172, 84, 1, 34, 5, 44, 630);
        check(90, 15, 30, 40, 4.4975, 102, 96, 3, 44, 3, 36, 305);
        check(40, 60, 95, 95, 2.84, 193, 63, 2, 31, 5, 58, 570);
    }

    private static void check(int b, int a, int sc, int intelligence, double radius, int dwell, int look,
                              int inside, int hunger, int points, double spread, int patience) {
        assertEquals(radius, FamilyDutyMath.patrolRadius(b, a), EPSILON);
        assertEquals(dwell, FamilyDutyMath.stationDwell(sc));
        assertEquals(look, FamilyDutyMath.lookEvery(intelligence));
        assertEquals(inside, FamilyDutyMath.insideCheckEvery(a));
        assertEquals(hunger, FamilyDutyMath.hungerBreak(sc));
        assertEquals(points, FamilyDutyMath.searchWaypoints(a, sc));
        assertEquals(spread, FamilyDutyMath.searchSpread(intelligence), EPSILON);
        assertEquals(patience, FamilyDutyMath.beckonPatience(a, sc));
    }

    @Test
    void patrolStaysBesideTheDoorAndRingsAnOpenNest() {
        var door = FamilyDutyMath.patrolOffsets(2, true);
        var nest = FamilyDutyMath.patrolOffsets(5, false);
        assertEquals(4, door.length);
        assertEquals(4, nest.length);
        for (var point : door) {
            assertEquals(2, Math.hypot(point[0], point[1]), EPSILON);
            assertTrue(Math.abs(point[1]) >= 1.7);
        }
        for (var point : nest) assertEquals(5, Math.hypot(point[0], point[1]), EPSILON);
    }

    @Test
    void sweepStartsAtTheSightingAndFansOutWithinRange() {
        var offsets = FamilyDutyMath.searchSweep(20, 0, 90, 12, 6, 60);
        assertEquals(6, offsets.length);
        assertArrayEquals(new double[]{12, 0}, offsets[0], EPSILON);
        for (int i = 1; i < offsets.length; i++) {
            var point = offsets[i];
            assertTrue(Math.hypot(point[0], point[1]) <= 12 + EPSILON);
            assertEquals(i % 2 == 1 ? 1 : -1, Math.signum(point[1]));
        }
        var nearby = FamilyDutyMath.searchSweep(.1, 0, 90, 12, 3, 30);
        assertArrayEquals(new double[]{.1, 0}, nearby[0], EPSILON);
        assertTrue(nearby[1][0] < 0 && nearby[2][0] > 0);
        assertTrue(nearby[1][1] > 0 && nearby[2][1] > 0);
    }
}
