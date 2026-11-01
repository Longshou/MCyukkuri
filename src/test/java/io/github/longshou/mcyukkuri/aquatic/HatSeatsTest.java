package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HatSeats;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HatSeatsTest {
    private static HatSeats.Body body(double size) { return new HatSeats.Body("marisa", size, .8 * size, .6 * size); }
    private static List<HatSeats.Occupant> captain(double scale) {
        var body = body(scale);
        return new ArrayList<>(List.of(new HatSeats.Occupant(body, HatSeats.admit(scale, body, true, List.of()), true)));
    }
    private static void board(List<HatSeats.Occupant> riders, double scale, double size) {
        var b = body(size); var at = HatSeats.admit(scale, b, false, riders);
        assertNotNull(at);
        riders.add(new HatSeats.Occupant(b, at, false));
    }
    @Test void twoInfantsOfDifferentSizesFitWithoutMovingTheCaptain() {
        var riders = captain(1); Vec original = riders.getFirst().at();
        board(riders, 1, .1); board(riders, 1, .2);
        assertNull(HatSeats.admit(1, body(.1), false, riders));
        assertEquals(original, riders.getFirst().at());
        assertNotEquals(riders.get(1).at(), riders.get(2).at());
    }
    @Test void oneSmallJuvenileFitsAndLargeJuvenileIsRefused() {
        var riders = captain(1);
        assertNotNull(HatSeats.admit(1, body(.38), false, riders));
        assertNull(HatSeats.admit(1, body(.39), false, riders));
        assertNull(HatSeats.admit(1, body(.42), false, riders));
        board(riders, 1, .35);
        assertNull(HatSeats.admit(1, body(.1), false, riders));
    }
    @Test void juvenilesCannotJoinInfants() {
        var riders = captain(1); board(riders, 1, .15);
        assertNull(HatSeats.admit(1, body(.3), false, riders));
    }
    @Test void captainRangeAndHullScaleKeepProportionalCapacity() {
        for (double size : new double[]{.8, 1, 1.2}) {
            var riders = captain(size);
            assertNotNull(HatSeats.admit(size, body(.38 * size), false, riders));
            board(riders, size, .15 * size); board(riders, size, .2 * size);
            assertNull(HatSeats.admit(size, body(size), true, riders));
        }
    }
    @Test void growthOutOfAnOccupiedSeatRequiresLeavingWithoutRepacking() {
        var riders = captain(1); board(riders, 1, .15);
        var infant = riders.get(1);
        assertTrue(HatSeats.fits(1, infant, List.of(riders.getFirst())));
        assertFalse(HatSeats.fits(1, new HatSeats.Occupant(body(.21), infant.at(), false), List.of(riders.getFirst())));
        var young = HatSeats.admit(1, body(.38), false, captain(1));
        assertFalse(HatSeats.fits(1, new HatSeats.Occupant(body(.39), young, false), captain(1)));
    }
}
