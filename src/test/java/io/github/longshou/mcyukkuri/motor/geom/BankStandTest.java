package io.github.longshou.mcyukkuri.motor.geom;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BankStandTest {
    @Test void fullTermBodyCannotBridgeTheBankButAnEarlierBodyCan() {
        assertTrue(1.169 > BankStand.widthLimit(1));
        assertTrue(1 < BankStand.widthLimit(1));
        assertEquals(1, .92 * BankStand.widthLimit(1), 1e-12);
    }

    @Test void nearestStandMakesShortCrossingReachableWithoutLeavingSupport() {
        var from = new Vec(.7, -.06, .28);
        var stand = BankStand.nearest(new Vec(0, 0, 0), new Vec(1, 0, 0), .12, from);
        assertEquals(new Vec(-.12, 0, .28), stand);
        assertTrue(stand.sub(from).hlen() < .85);
        assertTrue(new Vec(-.12, 0, 0).sub(from).hlen() > .85);
        assertTrue(stand.x() + .12 <= 0);
        assertTrue(stand.z() + .12 <= .5);
    }

    @Test void cornersStayOnTheBankCellForEveryFacing() {
        for (var normal : new Vec[]{new Vec(1, 0, 0), new Vec(-1, 0, 0), new Vec(0, 0, 1), new Vec(0, 0, -1)}) {
            var tangent = new Vec(-normal.z(), 0, normal.x());
            var edge = new Vec(12, 64, -8);
            for (int side : new int[]{-1, 1}) {
                var from = edge.add(normal.scale(2)).add(tangent.scale(side * 3));
                var stand = BankStand.nearest(edge, normal, .34, from);
                var expected = edge.sub(normal.scale(.34)).add(tangent.scale(side * .16));
                assertEquals(expected.x(), stand.x(), 1e-12);
                assertEquals(expected.y(), stand.y(), 1e-12);
                assertEquals(expected.z(), stand.z(), 1e-12);
            }
        }
    }
}
