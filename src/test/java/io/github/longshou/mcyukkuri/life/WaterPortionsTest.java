package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterPortionsTest {
    @Test void bucketFillsTheTank() {
        assertEquals(8, WaterPortions.refill(0, true));
        assertEquals(8, WaterPortions.refill(7, true));
    }

    @Test void bottleAddsThreeUpToCapacity() {
        assertEquals(3, WaterPortions.refill(0, false));
        assertEquals(7, WaterPortions.refill(4, false));
        assertEquals(8, WaterPortions.refill(7, false));
    }

    @Test void fullTankRefusesBothRefills() {
        assertEquals(8, WaterPortions.refill(8, true));
        assertEquals(8, WaterPortions.refill(8, false));
    }

    @Test void eachDrinkSpendsOnePortionUntilEmpty() {
        assertEquals(7, WaterPortions.spend(8));
        assertEquals(0, WaterPortions.spend(1));
        assertEquals(0, WaterPortions.spend(0));
    }
}
