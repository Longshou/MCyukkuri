package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.life.WaterDistress.Phase.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterEntryTest {
    @Test void aToeOnTheBankStillNeedsALandStep() {
        var bank = 302.0;
        var radius = .4; // PD-26 Marisa's half width at the south bank
        assertEquals(WaterDistress.BankFooting.EDGE, WaterDistress.BankFooting.of(true, 301.717 - radius >= bank));
        assertEquals(WaterDistress.BankFooting.STAND, WaterDistress.BankFooting.of(true, 302.5 - radius >= bank));
        assertEquals(WaterDistress.BankFooting.AIR, WaterDistress.BankFooting.of(false, true));
    }

    @Test void aWaderCanMoveImmediatelyButFallingOrBeingThrownStillHasAnImpact() {
        assertEquals(STRUGGLE, WaterDistress.Phase.entry(false, 0, false));
        assertEquals(IMPACT, WaterDistress.Phase.entry(true, 0, false));
        assertEquals(IMPACT, WaterDistress.Phase.entry(false, 12, false));
        assertEquals(IMPACT, WaterDistress.Phase.entry(false, 0, true));
    }
}
