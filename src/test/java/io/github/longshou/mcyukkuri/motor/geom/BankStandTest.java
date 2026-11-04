package io.github.longshou.mcyukkuri.motor.geom;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BankStandTest {
    @Test void fullTermBodyCanReachACentreSupportedBankWithoutIncreasingReach() {
        double width = 1.169;
        var from = new Vec(width / 2, -.08, .28);
        var stand = BankStand.nearest(Vec.ZERO, new Vec(1, 0, 0), from);
        assertTrue(stand.x() < 0 && stand.x() > -1);
        assertEquals(from.z(), stand.z());
        assertTrue(stand.sub(from).hlen() < 1);
        assertTrue(stand.x() + width / 2 > 0, "the body may overhang while its centre stays on land");
        assertInstanceOf(ArcSolver.Plan.class, ArcSolver.lowest(from, stand, .8, 1, 1, points -> {
            for (var p : points) {
                var feet = from.add(p);
                if (feet.x() - width / 2 < 0 && feet.y() < 0) return false;
            }
            return true;
        }));
    }

    @Test void centreIsStrictlyInsideTheBankEvenAtCornersAndNegativeCoordinates() {
        for (var normal : new Vec[]{new Vec(1, 0, 0), new Vec(-1, 0, 0), new Vec(0, 0, 1), new Vec(0, 0, -1)}) {
            var tangent = new Vec(-normal.z(), 0, normal.x());
            var edge = new Vec(-160, 64, 300.5);
            var centre = edge.sub(normal.scale(.5));
            for (int side : new int[]{-1, 1}) {
                var stand = BankStand.nearest(edge, normal, edge.add(normal.scale(2)).add(tangent.scale(side * 3)));
                assertTrue(stand.x() > centre.x() - .5 && stand.x() < centre.x() + .5);
                assertTrue(stand.z() > centre.z() - .5 && stand.z() < centre.z() + .5);
                assertEquals(edge.y(), stand.y());
            }
        }
    }
}
