package io.github.longshou.mcyukkuri.motor.geom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class ShelfApproachTest {
    @Test void founderOrReturnLosesAdmissionWhenAnotherBodyArrivesDuringDescent() {
        var min = new Vec(0, 64, 0);
        var max = new Vec(.8, 64.6, .8);
        boolean admitted = !ShelfApproach.occupied(false, min, max, new Vec(1, 64, 0), new Vec(1.75, 64.53, .75));
        assertEquals(ShelfApproach.Landing.APPROACH, ShelfApproach.landing(admitted, false, false));
        boolean stillClear = !ShelfApproach.occupied(false, min, max, new Vec(.7, 64, 0), new Vec(1.45, 64.53, .75));
        assertEquals(ShelfApproach.Landing.REPICK, ShelfApproach.landing(stillClear, false, false));
        assertEquals(ShelfApproach.Landing.REPICK, ShelfApproach.landing(stillClear, true, true));
        assertEquals(ShelfApproach.Landing.REPICK, ShelfApproach.landing(true, true, false));
        assertEquals(ShelfApproach.Landing.TOUCHDOWN, ShelfApproach.landing(true, true, true));
    }

    private static final Vec GOAL = new Vec(-290.5, 72, 491.5);

    @Test void landingRejectsThePreysCellAndAnyOverlapFromANeighbour() {
        // The failed WT-06 stand at (-347.5,64,291.5), and the Reimu at (-347.795,64,291.83).
        var min = new Vec(-347.9, 64, 291.1);
        var max = new Vec(-347.1, 64.6, 291.9);
        var preyMin = new Vec(-348.17, 64, 291.455);
        var preyMax = new Vec(-347.42, 64.53, 292.205);
        assertTrue(ShelfApproach.occupied(true, min, max, preyMin, preyMax));
        assertTrue(ShelfApproach.occupied(false, min, max, preyMin, preyMax));
        var shift = new Vec(1, 0, 0);
        assertFalse(ShelfApproach.occupied(false, min.add(shift), max.add(shift), preyMin, preyMax));
        assertTrue(ShelfApproach.occupied(true, min, max, new Vec(-347.05, 64, 291.4), new Vec(-347.01, 64.1, 291.45)));
        assertFalse(ShelfApproach.occupied(false, min, max, new Vec(-347.8, 64.6, 291.4), new Vec(-347.4, 65, 291.8)));
    }

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
