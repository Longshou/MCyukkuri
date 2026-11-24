package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The direction of the give-way decision: the stronger body keeps, the nearer place yields, a dead end behind costs the most. */
class YieldRuleTest {
    private static double score(double massA, double massB, boolean carryA, boolean carryB, double urgA, double urgB, int boldA, int boldB) {
        return YieldRule.score(massA, massB, carryA, carryB, urgA, urgB, boldA, boldB, .5, .5);
    }

    @Test void heavier_carrying_urgent_or_bolder_body_scores_as_keeping() {
        assertTrue(score(1.5, 1, false, false, 0, 0, 50, 50) > 0);
        assertTrue(score(1, 1, true, false, 0, 0, 50, 50) > 0);
        assertTrue(score(1, 1, false, false, 1, 0, 50, 50) > 0);
        assertTrue(score(1, 1, false, false, 0, 0, 90, 15) > 0);
        assertEquals(0, score(1, 1, false, false, 0, 0, 50, 50), 1e-9);
        assertEquals(-score(1.5, 1, true, false, .5, 0, 80, 20), score(1, 1.5, false, true, 0, .5, 20, 80), 1e-9);
    }

    @Test void stronger_body_pays_more_to_yield_and_so_keeps_at_equal_walks() {
        double strong = YieldRule.cost(3, score(1.5, 1, true, false, 1, 0, 90, 15)), weak = YieldRule.cost(3, score(1, 1.5, false, true, 0, 1, 15, 90));
        assertTrue(strong > weak);
        assertTrue(YieldRule.aYields(weak, strong, 2, 1));
        assertFalse(YieldRule.aYields(strong, weak, 1, 2));
    }

    @Test void nearer_place_yields_unless_the_other_is_much_weaker() {
        double equal = score(1, 1, false, false, 0, 0, 50, 50);
        assertTrue(YieldRule.aYields(YieldRule.cost(1, equal), YieldRule.cost(6, equal), 9, 1));
        assertFalse(YieldRule.aYields(YieldRule.cost(6, equal), YieldRule.cost(1, equal), 1, 9));
        // A timid body with a place twice as near yields; a heavy, carrying, urgent one with a much farther place keeps.
        double heavy = score(1.5, 1, true, false, 1, 0, 90, 15);
        assertFalse(YieldRule.aYields(YieldRule.cost(1, heavy), YieldRule.cost(1.5, -heavy), 1, 2));
    }

    @Test void dead_end_behind_costs_more_than_an_open_way_and_a_found_place() {
        double found = YieldRule.walkCost(true, 8, 8), open = YieldRule.walkCost(false, 0, YieldRule.REACH), wall = YieldRule.walkCost(false, 0, 3);
        assertTrue(wall > open);
        assertTrue(open > found);
        // The weaker body behind a wall never yields into it while the other has any way (even none within reach but open).
        double weakWall = YieldRule.cost(wall, -.6), strongOpen = YieldRule.cost(open, .6);
        assertTrue(YieldRule.aYields(strongOpen, weakWall, 1, 2));
    }

    @Test void exactly_one_yields_whoever_asks_and_the_one_that_cannot_never_does() {
        double[] costs = {0, 1.2, 1.3, 3, 3.1, 16, 36};
        for (boolean aCan : new boolean[] {true, false}) for (boolean bCan : new boolean[] {true, false}) {
            if (!aCan && !bCan) continue;
            for (double a : costs) for (double b : costs) for (int idA : new int[] {1, 2}) {
                int idB = 3 - idA;
                boolean fromA = YieldRule.aYields(aCan, bCan, a, b, idA, idB), fromB = YieldRule.aYields(bCan, aCan, b, a, idB, idA);
                assertTrue(fromA != fromB, "both or neither yield for " + a + "/" + b + " ids " + idA + "/" + idB);
                if (!aCan) assertFalse(fromA);
                if (!bCan) assertFalse(fromB);
                // The same inputs always give the same answer (the decision is stored once, but any later asker agrees).
                assertEquals(fromA, YieldRule.aYields(aCan, bCan, a, b, idA, idB));
            }
        }
    }

    @Test void ties_go_to_the_lower_id() {
        assertTrue(YieldRule.aYields(3, 3.1, 1, 2));
        assertFalse(YieldRule.aYields(3, 3.1, 2, 1));
    }

    @Test void a_given_up_walk_returns_only_after_a_real_change_not_on_the_clock() {
        // The same target and the same body standing where it was: refused however much time passes (the rule has no time input).
        assertFalse(YieldRule.refusalLifted(true, true, true, 0));
        assertFalse(YieldRule.refusalLifted(true, true, true, YieldRule.STAYED));
        assertTrue(YieldRule.refusalLifted(false, true, true, 0));
        assertTrue(YieldRule.refusalLifted(true, false, true, 0));
        assertTrue(YieldRule.refusalLifted(true, true, false, 0));
        assertTrue(YieldRule.refusalLifted(true, true, true, YieldRule.STAYED + .01));
    }
}
