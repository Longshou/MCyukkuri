package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.life.WaterDistress.Phase.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterEntryTest {
    @Test void aWaderCanMoveImmediatelyButFallingOrBeingThrownStillHasAnImpact() {
        assertEquals(STRUGGLE, WaterDistress.Phase.entry(false, 0, false));
        assertEquals(IMPACT, WaterDistress.Phase.entry(true, 0, false));
        assertEquals(IMPACT, WaterDistress.Phase.entry(false, 12, false));
        assertEquals(IMPACT, WaterDistress.Phase.entry(false, 0, true));
    }
}
