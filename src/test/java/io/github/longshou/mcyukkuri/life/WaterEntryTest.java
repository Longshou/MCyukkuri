package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.life.WaterDistress.Phase.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WaterEntryTest {
    private record Feet(double x, double y, double z) {}

    private static StandFace.Probe<Feet> bank(double width, boolean stair, boolean flooded) {
        return (x, y, z) -> {
            double wet = flooded || z - width / 2 < 302 ? 1 : 0;
            boolean stands = StandFace.supports(x, z, width * .42, wet,
                    () -> !stair || y >= 64 || z + width / 2 <= 302.5,
                    (px, pz) -> pz >= 302 && y == (stair && pz >= 302.5 ? 64 : 63.5));
            return stands ? new Feet(x, y, z) : null;
        };
    }

    @Test void halfSlabBankKeepsItsRealSoleHeight() {
        assertEquals(new Feet(.5, 63.5, 302.5),
                StandFace.stand(0, 1, 63.5, 302, 303, .4, bank(.8, false, false)));
    }

    @Test void stairBankOffersTheLowerTreadToAChildAndTheSupportedUpperTreadToItsParent() {
        assertEquals(new Feet(.5, 63.5, 302.25),
                StandFace.stand(0, 1, 63.5, 302, 302.5, .12, bank(.24, true, false)));
        assertNull(StandFace.stand(0, 1, 63.5, 302, 302.5, .4, bank(.8, true, false)));
        assertEquals(new Feet(.5, 64, 302.9),
                StandFace.stand(0, 1, 64, 302.5, 303, .4, bank(.8, true, false)));
    }

    @Test void waterCoveredTreadCannotEndAnEscape() {
        assertNull(StandFace.stand(0, 1, 63.5, 302, 303, .12, bank(.24, false, true)));
    }

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
