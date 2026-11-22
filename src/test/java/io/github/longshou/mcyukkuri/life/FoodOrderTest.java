package io.github.longshou.mcyukkuri.life;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FoodOrderTest {
    private static final UUID LOW = new UUID(0, 1), HIGH = new UUID(0, 2);

    @Test void theHungrierBodyGoesFirstWhateverTheIds() {
        assertTrue(FoodOrder.yieldsTo(60, LOW, 30, HIGH));
        assertFalse(FoodOrder.yieldsTo(30, HIGH, 60, LOW));
        assertTrue(FoodOrder.yieldsTo(60, HIGH, 35, LOW));
        assertFalse(FoodOrder.yieldsTo(35, LOW, 60, HIGH));
    }

    @Test void closeBodiesSplitByIdAndExactlyOneYields() {
        for (float gap : new float[]{0, 10, 24.9f, -24.9f}) {
            boolean a = FoodOrder.yieldsTo(50 + gap, LOW, 50, HIGH), b = FoodOrder.yieldsTo(50, HIGH, 50 + gap, LOW);
            assertNotEquals(a, b, "gap " + gap);
        }
    }

    @Test void orderIsAntisymmetricAcrossTheWholeGrid() {
        for (float x = 0; x <= 100; x += 2.5f) for (float y = 0; y <= 100; y += 2.5f)
            assertNotEquals(FoodOrder.yieldsTo(x, LOW, y, HIGH), FoodOrder.yieldsTo(y, HIGH, x, LOW), x + "/" + y);
    }

    @Test void inspectPauseSpansEightToEightyTicks() {
        assertEquals(80, FoodOrder.inspectTicks(1, 0));
        assertEquals(8, FoodOrder.inspectTicks(0, 1));
        assertEquals(20, FoodOrder.inspectTicks(0, 0));
        assertTrue(FoodOrder.inspectTicks(.9, .1) > FoodOrder.inspectTicks(.1, .9));
    }
}
