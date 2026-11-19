package io.github.longshou.mcyukkuri.motor.geom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class ShelfApproachTest {
    private static final Vec GOAL = new Vec(-290.5, 72, 491.5);

    @Test void leaveTheOverhangBeforeClimbingOntoTheShelf() {
        var stuck = new Vec(-290.5636557, 70.39999998, 492.3993233);
        // The body's half-width is .4. It can move below the log, above it, or rise in the outside column.
        BiPredicate<Vec, Vec> clear = (a, b) -> Math.max(a.y(), b.y()) <= stuck.y()
                || Math.min(a.y(), b.y()) >= GOAL.y() || Math.min(a.z(), b.z()) >= 492.4;
        var side = ShelfApproach.next(stuck, GOAL, clear);
        assertEquals(new Vec(-290.5, stuck.y(), 492.5), side);
        var rise = ShelfApproach.next(side, GOAL, clear);
        assertEquals(new Vec(side.x(), 72.08, side.z()), rise);
        assertEquals(GOAL, ShelfApproach.next(rise, GOAL, clear));
    }

    @Test void useTheDirectLegWhenOpenAndRefuseAnEnclosedShelf() {
        var from = new Vec(0, 0, 0);
        assertEquals(GOAL, ShelfApproach.next(from, GOAL, (a, b) -> true));
        assertNull(ShelfApproach.next(from, GOAL, (a, b) -> false));
    }
}
