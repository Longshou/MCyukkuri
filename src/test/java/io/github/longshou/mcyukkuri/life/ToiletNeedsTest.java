package io.github.longshou.mcyukkuri.life;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ToiletNeedsTest {
    @Test void foodWaterAndAwakeTimeAccumulateInTheirOwnAccounts() {
        var needs = new ToiletNeeds();
        needs.bowel = 72; // Older worlds only supply the solid load.
        assertEquals(0, needs.urine);
        needs.drank();
        needs.awakeInterval();
        assertEquals(3, needs.urine);
        assertEquals(72, needs.bowel);
        needs.ate(12);
        assertEquals(78, needs.bowel);
        assertEquals(3, needs.urine);
        needs.urine = 99;
        needs.drank();
        needs.awakeInterval();
        needs.ate(80);
        assertEquals(100, needs.urine);
        assertEquals(100, needs.bowel);
    }

    @Test void tieAndConstipationSelectAmongFeasibleNeeds() {
        var needs = new ToiletNeeds();
        needs.bowel = needs.urine = 90;
        assertEquals(ToiletNeeds.Kind.STOOL, needs.leading(true));
        assertEquals(ToiletNeeds.Kind.URINE, needs.leading(false));
        needs.drank();
        assertEquals(ToiletNeeds.Kind.URINE, needs.leading(true));
    }
}
