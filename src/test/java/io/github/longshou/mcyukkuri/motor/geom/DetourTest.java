package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Steering round footprints on the shorter side, clearance, hysteresis, neighbours, and moving a target out of a footprint. */
class DetourTest {
    /** An adult's foot radius (0.75 wide) and a land hat's brim at size 1 (7.21 x 10.13 hat units of 1/16 block). */
    private static final double FOOT = .395, A = .45, B = .63, STEP = .1;

    private static Detour.Box hat(double x, double z, double yawRad) {
        return new Detour.Box(1, x, z, A, B, Math.cos(yawRad), -Math.sin(yawRad));
    }

    /** Walks from (fx, fz) toward the target a fixed step a tick, re-steering each tick; returns {ticks, side changes, closest gap,
     *  most the way left ever grew in a tick}. */
    private static double[] walk(double fx, double fz, double tx, double tz, List<Detour.Box> boxes) {
        double x = fx, z = fz, closest = Double.POSITIVE_INFINITY, way = Double.POSITIVE_INFINITY, grew = 0;
        long id = 0;
        int side = 0, changes = 0;
        for (int tick = 1; tick <= 400; tick++) {
            var turn = Detour.steer(x, z, tx, tz, FOOT, boxes, id, side);
            if (Double.isFinite(way)) grew = Math.max(grew, turn.way() - way);
            way = turn.way();
            if (turn.side() != 0) {
                if (side != 0 && (turn.id() == id && turn.side() != side)) changes++;
                id = turn.id();
                side = turn.side();
            }
            double dx = turn.x() - x, dz = turn.z() - z, d = Math.hypot(dx, dz), step = Math.min(STEP, d);
            if (d > 1e-9) { x += dx / d * step; z += dz / d * step; }
            for (var b : boxes) closest = Math.min(closest, b.gap(x, z));
            if (Math.hypot(tx - x, tz - z) < 1e-6) return new double[]{tick, changes, closest, grew};
        }
        return new double[]{Double.NaN, changes, closest, grew};
    }

    @Test void nothingInTheWayHeadsStraight() {
        var turn = Detour.steer(0, 0, 3, 0, FOOT, List.of(hat(1.5, 3, 0)), 0, 0);
        assertEquals(0, turn.side());
        assertEquals(3, turn.x(), 1e-12);
        assertEquals(0, turn.z(), 1e-12);
    }

    @Test void aHatAcrossTheLineIsPassedByACornerOnTheShorterSide() {
        // the hat sits a little to +z of the line: going round its -z side is shorter
        var turn = Detour.steer(-2, 0, 2, 0, FOOT, List.of(hat(0, .2, 0)), 0, 0);
        assertTrue(turn.side() != 0);
        assertTrue(turn.z() < 0, "turns to the -z side");
        assertEquals(-(B - .2 + FOOT + Detour.PAST), turn.z(), 1e-9);
        assertEquals(-(A + FOOT + Detour.PAST), turn.x(), 1e-9);
    }

    @Test void aWalkRoundTheHatKeepsTheFootClearAndArrives() {
        // the father reaching a stand behind a turned hat (yaw 113 degrees, as in the 470bc06e take_hat walk)
        var boxes = List.of(hat(0, 0, Math.toRadians(113.4)));
        var result = walk(1.5, .5, -1.4, -.3, boxes);
        assertTrue(Double.isFinite(result[0]), "arrives");
        assertTrue(result[2] >= FOOT - 2e-3, "foot never over the brim: " + result[2]);
        assertTrue(result[0] < 60, "no wandering: " + result[0]);
        // the way left (a walk's metric while it steers round) never grows from one tick to the next
        assertTrue(result[3] <= 1e-6, "the way round never grows: " + result[3]);
    }

    @Test void aTargetRightBehindTheMiddleCommitsToOneSide() {
        var result = walk(-2, 0, 2, 0, List.of(Detour.Box.aligned(7, -.3, -.3, .3, .3)));
        assertTrue(Double.isFinite(result[0]));
        assertEquals(0, result[1], "never swaps sides");
    }

    @Test void theSideTakenIsKeptUntilTheOtherIsClearlyShorter() {
        var box = List.of(Detour.Box.aligned(7, -.3, -.3, .3, .3));
        // a little shorter by -z; having taken +z (side 1) it keeps it
        var fresh = Detour.steer(-2, -.05, 2, -.05, FOOT, box, 0, 0);
        var kept = Detour.steer(-2, -.05, 2, -.05, FOOT, box, 7, 1);
        assertEquals(-1, fresh.side());
        assertEquals(1, kept.side());
        assertTrue(kept.z() > 0);
        // much shorter by -z: it changes
        assertEquals(-1, Detour.steer(-2, -.5, 2, -.5, FOOT, box, 7, 1).side());
    }

    @Test void aTargetBesideAFootprintIsReachedWithLessClearance() {
        // a stand 0.2 from a body's box, approached from its far side
        var body = List.of(Detour.Box.aligned(3, -.3, -.3, .3, .3));
        var result = walk(-2, 0, .5, 0, body);
        assertTrue(Double.isFinite(result[0]), "arrives");
        assertTrue(result[2] >= .2 - 2e-3, "never closer than the stand itself: " + result[2]);
    }

    @Test void aBodyAlreadyInsideAFootprintIsLeftToCollision() {
        var turn = Detour.steer(0, 0, 3, 0, FOOT, List.of(Detour.Box.aligned(3, -.3, -.3, .3, .3)), 0, 0);
        assertEquals(0, turn.side());
    }

    @Test void aCornerInsideANeighbourIsClosed() {
        // a body standing at the hat's near -z corner: the way round goes by +z even though -z is a little shorter
        var boxes = List.of(hat(0, .05, 0), Detour.Box.aligned(9, -1.2, -1.4, -.6, -.8));
        assertEquals(-1, Detour.steer(-2, 0, 2, 0, FOOT, boxes.subList(0, 1), 0, 0).side());
        var turn = Detour.steer(-2, 0, 2, 0, FOOT, boxes, 0, 0);
        assertEquals(1, turn.id());
        assertTrue(turn.z() > 0, "takes the open side");
        assertTrue(Double.isFinite(walk(-2, 0, 2, 0, boxes)[0]));
    }

    @Test void aTargetUnderTheBrimMovesToTheNearestClearPoint() {
        var exits = Detour.outside(.2, .1, FOOT, List.of(hat(0, 0, 0)));
        var p = exits.get(0);
        // nearest exit is across the short (x) half extent
        assertEquals(A + FOOT + Detour.PAST, p[0], 1e-9);
        assertEquals(.1, p[1], 1e-9);
        assertTrue(hat(0, 0, 0).gap(p[0], p[1]) >= FOOT);
        for (int i = 1; i < exits.size(); i++)
            assertTrue(Math.hypot(exits.get(i)[0] - .2, exits.get(i)[1] - .1) >= Math.hypot(p[0] - .2, p[1] - .1));
    }

    @Test void aClearTargetStaysAndAnEnclosedOneHasNoExit() {
        var free = Detour.outside(3, 0, FOOT, List.of(hat(0, 0, 0)));
        assertEquals(1, free.size());
        assertEquals(3, free.get(0)[0], 1e-12);
        // hats on all four sides of the target: every exit lands under another brim
        var ring = List.of(hat(0, 0, 0), hat(1.3, 0, 0), hat(-1.3, 0, 0), new Detour.Box(2, 0, 1.6, A, B, 1, 0),
                new Detour.Box(3, 0, -1.6, A, B, 1, 0));
        assertTrue(Detour.outside(0, 0, FOOT, ring).isEmpty());
    }

    @Test void aTurnedFootprintMeasuresAlongItsOwnAxes() {
        var turned = hat(0, 0, Math.PI / 2);
        // its long half extent (b) now lies along world x
        assertEquals(0, turned.gap(B, 0), 1e-9);
        assertEquals(0, turned.gap(0, A), 1e-9);
        assertTrue(turned.gap(A + .01, B) > 0);
    }
}
