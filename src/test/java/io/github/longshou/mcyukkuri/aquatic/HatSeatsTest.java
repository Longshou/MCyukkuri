package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HatSeats;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
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
    @Test void thirdNewbornHasNoSeatWhenTwoInfantSeatsAreTaken() {
        var riders = captain(1);
        board(riders, 1, .08); board(riders, 1, .08);
        assertNull(HatSeats.admit(1, body(.08), false, riders));
    }
    @Test void youngBelowSharedLimitFitsAndLimitIsRefused() {
        var riders = captain(1);
        double limit = HatSeats.youngLimit(1);
        assertEquals(.38, limit);
        assertNotNull(HatSeats.admit(1, body(Math.nextDown((float) limit)), false, riders));
        assertNull(HatSeats.admit(1, body(limit), false, riders));
        assertNull(HatSeats.admit(1, body(.39), false, riders));
        assertNull(HatSeats.admit(1, body(.42), false, riders));
        board(riders, 1, .35);
        assertNull(HatSeats.admit(1, body(.1), false, riders));
    }
    @Test void juvenilesCannotJoinInfants() {
        var riders = captain(1); board(riders, 1, .15);
        assertNull(HatSeats.admit(1, body(.3), false, riders));
    }
    @Test void brimPlayLeavesTheSeatUnreservedAndReturnRechecksAdmission() {
        var riders = captain(1);
        var playing = new HatSeats.Occupant(body(.3), HullBounds.brim(.3, -Math.PI / 2), false);
        assertFalse(HatSeats.seated(1, playing));
        riders.add(playing);
        var newcomer = HatSeats.admit(1, body(.3), false, riders);
        assertNotNull(newcomer);
        assertNull(HatSeats.admit(1, body(.3), false, List.of(riders.getFirst(), new HatSeats.Occupant(body(.3), newcomer, false))));
    }
    @Test void captainRangeAndHullScaleKeepProportionalCapacity() {
        for (double size : new double[]{.8, 1, 1.2}) {
            var riders = captain(size);
            assertNotNull(HatSeats.admit(size, body(Math.nextDown((float) HatSeats.youngLimit(size))), false, riders));
            board(riders, size, .15 * size); board(riders, size, .2 * size);
            assertNull(HatSeats.admit(size, body(size), true, riders));
        }
    }
    @Test void growthOutOfAnOccupiedSeatRequiresLeavingWithoutRepacking() {
        var riders = captain(1); board(riders, 1, .15);
        var infant = riders.get(1);
        assertTrue(HatSeats.fits(1, infant, List.of(riders.getFirst())));
        assertFalse(HatSeats.fits(1, new HatSeats.Occupant(body(.21), infant.at(), false), List.of(riders.getFirst())));
        var young = HatSeats.admit(1, body(.37), false, captain(1));
        assertFalse(HatSeats.fits(1, new HatSeats.Occupant(body(HatSeats.youngLimit(1)), young, false), captain(1)));
    }
    @Test void sharedLimitMatchesTheRealAuthoredCaptainAndYoungFootprints() {
        var riders = captain(1);
        var parent = riders.getFirst();
        double limit = HatSeats.youngLimit(1);
        var below = body(Math.nextDown((float) limit));
        var at = HatSeats.admit(1, below, false, riders);
        assertNotNull(at);
        assertTrue(HatSeats.fits(1, new HatSeats.Occupant(below, at, false), List.of(parent)));
        var exact = body(limit);
        var youngShape = BodyFootprint.of(exact.asset(), exact.width(), exact.height(), 0);
        var parentBody = parent.body();
        var parentShape = BodyFootprint.of(parentBody.asset(), parentBody.width(), parentBody.height(), 0);
        assertFalse(youngShape.overlaps(at.x(), at.y(), at.z(), parentShape,
                parent.at().x(), parent.at().y(), parent.at().z(), 0));
        assertNull(HatSeats.admit(1, exact, false, riders));
        assertFalse(HatSeats.fits(1, new HatSeats.Occupant(exact, at, false), List.of(parent)));
        assertEquals(.38 * 1.2, HatSeats.youngLimit(1.2));
    }
    @Test void sharedLimitLeavesClearanceBeforeActualSeatContact() {
        var parent = captain(1).getFirst();
        var parentBody = parent.body();
        var parentShape = BodyFootprint.of(parentBody.asset(), parentBody.width(), parentBody.height(), 0);
        double first = -1;
        for (int i = 30; i <= 60; i++) {
            var young = body(i / 100.0);
            var at = HatSeats.admit(1, body(.37), false, List.of(parent));
            var youngShape = BodyFootprint.of(young.asset(), young.width(), young.height(), 0);
            if (youngShape.overlaps(at.x(), at.y(), at.z(), parentShape, parent.at().x(), parent.at().y(), parent.at().z(), 0)) {
                first = i / 100.0; break;
            }
        }
        assertEquals(.41, first);
        assertEquals(.03, first - HatSeats.youngLimit(1), 1E-9);
    }
}
