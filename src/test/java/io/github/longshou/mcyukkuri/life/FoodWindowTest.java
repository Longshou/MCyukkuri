package io.github.longshou.mcyukkuri.life;

import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodWindowTest {
    @Test void hungerAndBoldnessShiftTheNoticeRange() {
        assertEquals(4, FoodWindow.heldFoodRange(75, 0), 1e-9);
        assertEquals(6, FoodWindow.heldFoodRange(75, 50), 1e-9);
        assertEquals(9, FoodWindow.heldFoodRange(45, 50), 1e-9);
        assertEquals(12, FoodWindow.heldFoodRange(15, 50), 1e-9);
        assertEquals(14, FoodWindow.heldFoodRange(0, 100), 1e-9);
        assertTrue(FoodWindow.heldFoodRange(45, 65) > 9);
        assertTrue(FoodWindow.heldFoodRange(45, 35) < 9);
    }

    @Test void villageEstimateIsConditionalOnStraysAndUsablePanes() {
        DoubleBinaryOperator revised = (nutrition, boldness) -> FoodWindow.heldFoodRange((float) nutrition, (int) boldness);
        DoubleBinaryOperator baseline = (nutrition, boldness) -> 4 + 4 * Math.clamp((75 - nutrition) / 60, 0, 1);
        assertEquals(1.06, expectedPresses(baseline, 2, .35), .01);
        assertEquals(1.60, expectedPresses(revised, 2, .35), .01);
        assertEquals(2.40, expectedPresses(revised, 3, .35), .01);
        assertEquals(.23, expectedPresses(revised, 2, .05), .01);
        assertEquals(0, expectedPresses(revised, 0, .35));
        assertEquals(0, expectedPresses(revised, 2, 0));
    }

    private static double expectedPresses(DoubleBinaryOperator range, int streetStrays, double usablePaneFraction) {
        // Twelve daylight passes separated by more than the 400-tick cooldown. A uniform 16-block lateral strip
        // approximates minimum separation. The pane, free-planner and completion fractions are scenario inputs.
        int awakeTicks = 13_000;
        int metabolismTicks = 240;
        double noticed = 0;
        for (int tick = 0; tick < awakeTicks; tick += metabolismTicks) {
            int loss = 2 * (tick / metabolismTicks);
            // Equal populations on two illustrative feeding cycles, starting at 40 and 80 after earlier meals.
            int sparse = 20 + Math.floorMod(40 - 20 - loss, 22);
            int fed = 66 + Math.floorMod(80 - 66 - loss, 16);
            // Spawn weights grouped by boldness centre (43, 50, 57), with a three-point approximation of SD 15.
            for (int centre : new int[]{43, 50, 57}) {
                double speciesWeight = centre == 43 ? .25 : .375;
                for (int shift : new int[]{-21, 0, 21}) {
                    double traitWeight = shift == 0 ? .5 : .25;
                    double fedRange = fed <= 75 ? range.applyAsDouble(fed, centre + shift) : 0;
                    double meanRange = (range.applyAsDouble(sparse, centre + shift) + fedRange) / 2;
                    noticed += Math.min(metabolismTicks, awakeTicks - tick) * speciesWeight * traitWeight * meanRange / 16;
                }
            }
        }
        return 12 * streetStrays * usablePaneFraction * .60 * .70 * (noticed / awakeTicks);
    }
}
