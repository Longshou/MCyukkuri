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

    @Test void aReleasedPortionCanOnlyBeSpentOnce() {
        var needs = new ToiletNeeds();
        needs.bowel = 83;
        needs.urine = 65;
        var selected = needs.leading(true);
        for (int i = 0; i < 12; i++) needs.drank();
        assertEquals(ToiletNeeds.Kind.STOOL, selected);
        assertEquals(83, needs.bowel);
        assertEquals(20, needs.dischargeUrine(20));
        assertEquals(69, needs.dischargeUrine(100));
        assertEquals(0, needs.dischargeUrine(20));
        assertEquals(83, needs.bowel);
    }

    @Test void onlyTheSelectedOutputCommitsItsQuantityAtContact() {
        var needs = new ToiletNeeds();
        needs.bowel = 75;
        needs.urine = 65;
        for (var kind : ToiletNeeds.Kind.values()) {
            assertFalse(needs.release(kind, () -> false, () -> false));
            assertEquals(75, needs.bowel);
            assertEquals(65, needs.urine);
        }
        int[] solidPortions = {0};
        assertTrue(needs.release(ToiletNeeds.Kind.URINE, () -> fail("urine produced stool"), () -> true));
        assertEquals(0, needs.urine);
        assertEquals(75, needs.bowel);
        assertFalse(needs.release(ToiletNeeds.Kind.URINE, () -> fail(), () -> fail("spent urine again")));
        assertTrue(needs.release(ToiletNeeds.Kind.STOOL, () -> ++solidPortions[0] == 1, () -> fail("stool emitted urine")));
        assertEquals(1, solidPortions[0]);
        assertEquals(35, needs.bowel);
        assertFalse(needs.release(ToiletNeeds.Kind.STOOL, () -> fail("spent stool again"), () -> fail()));
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
