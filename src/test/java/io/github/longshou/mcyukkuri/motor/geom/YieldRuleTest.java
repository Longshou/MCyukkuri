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

    @Test void ties_go_to_the_lower_id() {
        assertTrue(YieldRule.aYields(3, 3.1, 1, 2));
        assertFalse(YieldRule.aYields(3, 3.1, 2, 1));
    }
}
