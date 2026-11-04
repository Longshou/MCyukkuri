package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.motor.geom.BankStand;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PregnancyCurveTest {
    @Test void shorePointInvertsTheVisibleUnbornGrowth() {
        for (double progress : new double[]{.05, .25, .5, .75, .95}) {
            double width = .8 * PregnancyCurve.widthScale(PregnancyCurve.belly((float) progress, 0, 3));
            assertEquals(progress, PregnancyCurve.shoreFraction(.8, width), 1e-6);
        }
    }

    @Test void shorePointUsesTheRestingHopLimitAndDoesNotMoveDuringDelivery() {
        double limit = BankStand.widthLimit(1), point = PregnancyCurve.shoreFraction(.8, limit);
        assertTrue(point > 0 && point < 1);
        assertEquals(limit, .8 * PregnancyCurve.widthScale(PregnancyCurve.belly((float) point, 0, 3)), 1e-6);
        assertTrue(PregnancyCurve.belly(1, 2, 3) < PregnancyCurve.belly((float) point, 0, 3));
        assertTrue(1 > point, "a smaller belly during delivery remains past the same point");
        assertEquals(0, PregnancyCurve.shoreFraction(.8, .7), 1e-12);
        assertEquals(1, PregnancyCurve.shoreFraction(.8, 2), 1e-12);
    }
}
