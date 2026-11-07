package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Two bodies side by side meet where their side reaches, added at the same height and depth, are widest. */
class SideProfileTest {
    private static final double UNIT = 1 / 16.0;

    /** A body of the given half width over four unit cells of height and depth, depths from -2. */
    private static SideProfile box(double half) {
        var reach = new double[4][4];
        for (var row : reach) java.util.Arrays.fill(row, half);
        return new SideProfile(1, -2, reach);
    }

    @Test void plainBodiesMeetAtTheirTwoHalfWidths() {
        assertEquals((6 + 7) * UNIT, SideProfile.touching(box(6), UNIT, box(7), UNIT, 0, 0), 1e-9);
    }

    @Test void aBowOnlyAtTheBackBlocksBesideAnotherBowButPassesWhenOneStandsBack() {
        var reach = new double[4][4];
        for (var row : reach) { java.util.Arrays.fill(row, 6); row[3] = 9; }   // the bow sticks out at the back cell
        var bowed = new SideProfile(1, -2, reach);
        assertEquals(18 * UNIT, SideProfile.touching(bowed, UNIT, bowed, UNIT, 0, 0), 1e-9);
        // one unit back, the second's bow sits behind the first's: body meets bow (6 + 9)
        assertEquals(15 * UNIT, SideProfile.touching(bowed, UNIT, bowed, UNIT, 0, UNIT), 1e-9);
        var stand = SideProfile.closest(bowed, UNIT, bowed, UNIT, 0, new double[] {-UNIT, 0, UNIT});
        assertEquals(15 * UNIT, stand.across(), 1e-9);
        assertEquals(UNIT, Math.abs(stand.behind()), 1e-9);
    }

    @Test void aShortBodyFitsUnderAHighBrim() {
        var reach = new double[4][4];
        reach[0] = new double[] {5, 5, 5, 5}; reach[3] = new double[] {10, 10, 10, 10};   // a brim at the top, nothing between
        var hatted = new SideProfile(1, -2, reach);
        var small = new SideProfile(1, -2, new double[][] {{4, 4, 4, 4}, {4, 4, 4, 4}});
        assertEquals(9 * UNIT, SideProfile.touching(hatted, UNIT, small, UNIT, 0, 0), 1e-9);
        // raised two units, the small one's top reaches the brim
        assertEquals(14 * UNIT, SideProfile.touching(hatted, UNIT, small, UNIT, 2 * UNIT, 0), 1e-9);
    }

    @Test void aSmallerScaleShrinksItsReach() {
        assertEquals((6 + 3) * UNIT, SideProfile.touching(box(6), UNIT, box(6), UNIT / 2, 0, 0), 1e-9);
    }
}
