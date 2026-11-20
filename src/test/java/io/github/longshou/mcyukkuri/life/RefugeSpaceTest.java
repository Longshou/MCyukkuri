package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.motor.geom.SweptBody.Box;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefugeSpaceTest {
    private static Box at(double x, double z, double width, double height) {
        return new Box(new Vec(x - width / 2, 64, z - width / 2), new Vec(x + width / 2, 64 + height, z + width / 2));
    }

    @Test void aTallBodyTouchesOpenFireAtHeadHeight() {
        RefugeSpace.Hazard flame = (x, y, z) -> x == 0 && y == 65 && z == 0;
        assertTrue(RefugeSpace.touchesHazard(at(.5, .5, .90, 1.06), flame));
        assertFalse(RefugeSpace.touchesHazard(at(.5, .5, .90, 1), flame));
    }

    @Test void offCentreRestChecksLavaAcrossTheBodyEdge() {
        RefugeSpace.Hazard lava = (x, y, z) -> x == 1 && y == 65 && z == 0;
        assertTrue(RefugeSpace.touchesHazard(at(.9, .5, .90, 1.06), lava));
        assertFalse(RefugeSpace.touchesHazard(at(.5, .5, .90, 1.06), lava));
    }

    @Test void offCentreArrivalOverlapsTheBabyAlreadyRestingThere() {
        var resting = at(.82, .5, .135, .15);
        assertFalse(RefugeSpace.blocksStand(at(.50, .5, .135, .15), resting, true));
        assertTrue(RefugeSpace.blocksStand(at(.87, .5, .135, .15), resting, true));
    }

    @Test void aPassingChildClearsTheParentAndOnlyARealIntersectionBlocksArrival() {
        var parent = at(.2, .45, .45, .53);
        var passing = at(.8, .16, .135, .15);
        var intersecting = at(.38, .45, .135, .15);
        assertFalse(RefugeSpace.blocksStand(passing, parent, true));
        assertFalse(RefugeSpace.blocksStand(parent, intersecting, false));
        assertTrue(RefugeSpace.blocksStand(intersecting, parent, true));
    }
}
