package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Occupancy by height: footprints conflict only where both their circles and their heights overlap. */
class DiscTest {
    private static final double SLACK = .02;

    @Test void sameHeightOverlapsAsCircles() {
        assertTrue(new Disc(0, 0, 1, 0, 1).overlaps(new Disc(1.5, 0, 1, .5, 2), SLACK));
        assertFalse(new Disc(0, 0, 1, 0, 1).overlaps(new Disc(3, 0, 1, 0, 1), SLACK));
    }

    @Test void aBodyAboveAnothersTopDoesNotMeetIt() {
        // the owner standing on the brim over the hollow, a newborn and a stored portion on the hollow floor under her
        var owner = new Disc(0, 0, 4.8, 3.2, 12);
        assertFalse(owner.overlaps(new Disc(1, 0, 1.3, 1.14, 2.38), SLACK));
        assertFalse(owner.overlaps(new Disc(-1, 1, 1.03, 1.14, 1.82), SLACK));
        // a portion on a ledge high enough to reach her feet does meet her
        assertTrue(owner.overlaps(new Disc(2, 0, 1.03, 3.02, 3.71), SLACK));
    }

    @Test void touchingHeightsDoNotOverlap() {
        assertFalse(Disc.level(0, 1, 1, 2));
        assertTrue(Disc.level(0, 1, .99, 2));
    }

    @Test void aFootprintWithoutHeightMeetsEveryHeight() {
        assertTrue(new Disc(0, 0, 1).overlaps(new Disc(1, 0, 1, 50, 51), SLACK));
        assertTrue(new Disc(1, 0, 1, -7, -6).overlaps(new Disc(0, 0, 1), SLACK));
    }

    @Test void squeezeLeavesStackedBodiesAndLowStoresAlone() {
        var high = new Squeeze.Body(0, 0, 1, 3, 9, 3, 6);
        var low = new Squeeze.Body(.5, 0, .5, 1, 9, 1, 2.5);
        for (var m : Squeeze.separate(List.of(high, low), List.of(new Disc(-.8, 0, .3, 1, 1.7)), SLACK, 0, 0)) {
            assertEquals(0, Math.hypot(m[0], m[1]), 1e-12);
            assertEquals(0, m[2], 1e-12);
        }
        // the same pair at one height parts as before
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 1, 9, 0, 1), new Squeeze.Body(1, 0, 1, 1, 9, 0, 1)), List.of(), SLACK, 0, 0);
        assertEquals((2 - SLACK - 1) / 8, moves[1][0], 1e-12);
    }
}
