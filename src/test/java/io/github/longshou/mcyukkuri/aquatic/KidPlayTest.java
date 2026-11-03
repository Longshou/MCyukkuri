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
    @Test void hideUsesTheAuthoredRingAndActualUnderside() {
        var hide = HullFloorTest.brim(.3, 0);
        assertEquals(0, hide.z(), 1e-12);
        assertEquals(-.0095, hide.y(), .003);
        assertEquals(-.0033, HullFloorTest.brim(.45, 0).y(), .003);
        assertTrue(HullFloorTest.brim(.375, 0).y() > hide.y());
        assertTrue(HullFloorTest.brim(.375, 0).y() < HullFloorTest.brim(.45, 0).y());
        assertTrue(hide.x() > HullBounds.LEVEL_RADIUS && hide.x() < HullBounds.RADIUS);
    }
}
