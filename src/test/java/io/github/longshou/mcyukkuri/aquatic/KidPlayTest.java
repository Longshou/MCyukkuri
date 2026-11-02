package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KidPlayTest {
    @Test void fallingUsesTheAuthoredHardRockPhase() {
        assertTrue(HullPresentation.brimFall("ROCKING"));
        assertFalse(HullPresentation.brimFall("UPRIGHT"));
        assertFalse(HullPresentation.brimFall("FLIPPING"));
    }
    @Test void hideUsesTheAuthoredRingAndInterpolatedUnderside() {
        var hide = HullBounds.brim(.3, 0);
        assertEquals(0, hide.z(), 1e-12);
        assertEquals(-.0095, hide.y(), 1e-12);
        assertEquals(-.0033, HullBounds.brim(.45, 0).y(), 1e-12);
        assertEquals((-.0095 - .0033) / 2, HullBounds.brim(.375, 0).y(), 1e-12);
        assertTrue(hide.x() > HullBounds.LEVEL_RADIUS && hide.x() < HullBounds.RADIUS);
    }
}
