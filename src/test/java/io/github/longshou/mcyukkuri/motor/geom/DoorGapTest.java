package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoorGapTest {
    private static DoorGap framed(double width, boolean leftLeaf) {
        var leaf = leftLeaf ? new DoorGap.Span(0, 3/16.) : new DoorGap.Span(13/16., 1);
        var solids = List.of(new DoorGap.Span(1, 2), leaf, new DoorGap.Span(-1, 0));
        return DoorGap.fit(.5, width / 2, new ArrayList<>(solids));
    }

    @Test void usesTheGapForEitherHingeAndBothDirections() {
        for (boolean left : List.of(false, true)) {
            var gap = framed(.8f, left);
            double lane = gap.lane(.5);
            assertEquals(left ? 19/32. : 13/32., lane, 1e-9);
            assertEquals(-.4, gap.aimAlong(.5, -1, -.4, 1.4, 1.4));
            assertEquals(1.4, gap.aimAlong(lane, -.4, -.4, 1.4, 1.4));
            assertEquals(1.4, gap.aimAlong(.5, 2, -.4, 1.4, -.4));
            assertEquals(-.4, gap.aimAlong(lane, 1.4, -.4, 1.4, -.4));
        }
    }

    @Test void acceptsExactContactAndRejectsAWiderBody() {
        assertEquals(13/32., framed(13/16., false).lane(.5));
        assertNull(framed(13/16. + 1e-6, false));
        assertEquals(.5, framed(.5, false).lane(.5));
    }

    @Test void nearJambIsAnAlignmentStepEvenWhenLessThanAMillimetreRemains() {
        double radius = 13/32., entry = -radius, exit = 1 + radius;
        var gap = framed(2 * radius, false);
        double lane = gap.lane(.5);
        assertEquals(entry, gap.aimAlong(lane + .0005, entry, entry, exit, exit));
        assertFalse(gap.reached(lane + .0005, entry, .5, exit, 1));
        assertEquals(exit, gap.aimAlong(Math.nextUp(lane), entry, entry, exit, exit));
        assertTrue(gap.reached(lane, exit + .01, .5, exit, 1));
        assertFalse(gap.reached(lane, exit, .5, entry, -1));
        assertTrue(gap.reached(lane, entry - .01, .5, entry, -1));
        assertFalse(gap.reached(lane + .0005, .5, .5, .5, 0));
        assertTrue(gap.reached(lane, .5, .5, .5, 0));
    }

    @Test void doubleDoorUsesTheFullOpeningBetweenBothLeaves() {
        var leaves = List.of(new DoorGap.Span(0, 3/16.), new DoorGap.Span(2 - 3/16., 2));
        assertEquals(1, DoorGap.fit(1, .8, new ArrayList<>(leaves)).lane(1));
        assertNull(DoorGap.fit(1, .82, new ArrayList<>(leaves)));
    }
}
