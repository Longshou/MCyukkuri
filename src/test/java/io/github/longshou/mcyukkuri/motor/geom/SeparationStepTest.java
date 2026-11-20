package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeparationStepTest {
    @Test void stackedRestingBodiesPartByTheirOwnWidthsAndStop() {
        for (double width : new double[]{.16, (.16 + .8) / 2, .8, 1.6}) {
            double x = 0;
            for (int tick = 0; tick < 150; tick++) {
                var first = SeparationStep.crowd(x, 0, width, tick > 0, -1, .6, Vec.ZERO, step -> true);
                var second = SeparationStep.crowd(-x, 0, width, tick > 0, 1, 1, Vec.ZERO, step -> true);
                assertTrue(first.x() <= 0 && second.x() >= 0);
                assertTrue(first.hlen() <= SeparationStep.SHOVE * .6 && second.hlen() <= SeparationStep.SHOVE);
                x += first.x() - second.x();
            }
            assertTrue(-x >= width + SeparationStep.RELEASE_GAP);
            assertSame(Vec.ZERO, SeparationStep.crowd(x, 0, width, false, -1, 1, Vec.ZERO, step -> true));
        }
    }

    @Test void aReleasedPairDoesNotStartMovingInsideTheDaylightBand() {
        double x = .8 + SeparationStep.RELEASE_GAP / 2;
        assertSame(Vec.ZERO, SeparationStep.crowd(x, 0, .8, false, 1, 1, Vec.ZERO, step -> true));
        var finishing = SeparationStep.crowd(x, 0, .8, true, 1, 1, Vec.ZERO, step -> true);
        assertEquals(.8 + SeparationStep.RELEASE_GAP, x + finishing.x(), 1e-12);
    }

    @Test void roamingStepCannotEnterTheBodyAndAnIdleBodyOnlyStepsOntoOpenGround() {
        Vec inbound = new Vec(-.1, -.08, 0);
        var pass = SeparationStep.crowd(.85, 0, .8, false, 1, 1, inbound, step -> true);
        assertTrue(.85 + pass.x() >= .8 && pass.hlen() <= inbound.hlen());
        var resting = new Vec(0, -.08, 0);
        var slide = SeparationStep.crowd(.1, .02, .8, false, 1, 1, resting, s -> s.x() == 0 && s.z() > 0);
        assertEquals(new Vec(0, -.08, SeparationStep.SHOVE), slide);
        assertEquals(resting, SeparationStep.crowd(.1, .02, .8, false, 1, 1, resting, s -> false));
    }

    @Test void opensTheGapFromTheArchivedPeerRubStandWhenAStandTurnsInward() {
        double x = -.9443758;
        double clearance = .8 + .6 * .5;
        Vec turnIn = new Vec(.0335, -.0784, -.1116);
        for (int step = 0; step < 5; step++) {
            Vec moved = SeparationStep.away(x, -.1, clearance, turnIn, stepAt -> true);
            assertTrue(x + moved.x() <= -Math.min(-x, clearance));
            assertTrue(moved.hlen() <= turnIn.hlen() + 1e-12);
            assertEquals(turnIn.y(), moved.y());
            x += moved.x();
        }
        assertTrue(-x >= clearance);
    }

    @Test void persistentInwardSteeringHoldsAtEitherBoxedFlankAndResumesWhenItOpens() {
        for (int side : new int[]{-1, 1}) {
            double z = 0;
            for (int tick = 0; tick < 100; tick++) {
                double current = side * z;
                var step = SeparationStep.crowd(.86, z, .8, tick > 0, 1, 1, new Vec(-.1, -.08, 0),
                        candidate -> current + side * candidate.z() >= 0 && current + side * candidate.z() <= .1);
                assertEquals(0, step.x(), 1e-12);
                assertEquals(tick == 0 ? side * .1 : 0, step.z(), 1e-12);
                z += step.z();
            }
            var open = SeparationStep.crowd(.86, z, .8, true, 1, 1, new Vec(-.1, -.08, 0), candidate -> true);
            assertEquals(side * .1, open.z());
        }
    }

    @Test void aSupportedDiagonalCannotCarryTheBodyOntoItsUnsupportedClippedAxis() {
        double sole = .16 * .42;
        var scan = new ContactScan<>(peer -> true, (x, y, z) -> y == 0 && (x + sole <= .09 || z - sole >= .01));
        var step = SeparationStep.crowd(.10, .02, .16, false, 1, 1, new Vec(.11, -.08, .15),
                candidate -> scan.test(candidate.x(), 0, candidate.z()));
        scan.guard();
        assertTrue(scan.permitsResolved(step.x(), 0, step.z()));
        assertFalse(scan.permitsResolved(step.x(), 0, 0)); // Vanilla clipped Z against the west wall.
        assertTrue(scan.permitsResolved(0, 0, step.z()));
        assertFalse(scan.permitsResolved(0, .5, step.z()));
        assertTrue(scan.permitsResolved(0, -.08, 0));
    }

    @Test void eachMoveReusesTheNeighbourBufferAndProbesEachConsecutiveFootingOnce() {
        int[] probes = {0};
        var scan = new ContactScan<String>(s -> !s.isEmpty(), (x, y, z) -> ++probes[0] > 0);
        var neighbours = scan.neighbours;
        for (int i = 0; i < 1_000; i++) {
            scan.accept("");
            scan.accept("peer");
            assertSame(neighbours, scan.neighbours);
            assertEquals(1, neighbours.size());
            assertTrue(scan.test(.1, 0, .12));
            scan.guard();
            assertTrue(scan.permitsResolved(.1, 0, .12));
            assertEquals(i + 1, probes[0]);
            scan.clear();
            assertTrue(neighbours.isEmpty());
            assertTrue(scan.permitsResolved(.07, 0, .03)); // Unconstrained travel skips the probe.
        }
    }

    @Test void aSweepCannotCrossThePartnerEvenIfItsEndpointIsClear() {
        Vec moved = SeparationStep.away(1.04, 0, 1, new Vec(-2.1, 0, 0), step -> true);
        assertEquals(0, moved.x());
        assertTrue(Math.abs(moved.z()) > 0);
    }

    @Test void passesTangentAndOutwardTravelThroughUnchangedOnceClear() {
        Vec wanted = new Vec(-.1, -.08, .01);
        assertSame(wanted, SeparationStep.away(-1.04, .5, 1, wanted, step -> true));
        assertSame(wanted, SeparationStep.away(.6, 1.04, 1, wanted, step -> true));
    }

    @Test void slidesAlongTheOpenSideOfABrinkAndKeepsGravityWhenBoxedIn() {
        Vec wanted = new Vec(-.1, -.08, 0);
        Vec slide = SeparationStep.away(.9, 0, 1, wanted, step -> step.x() == 0 && step.z() < 0);
        assertEquals(0, slide.x(), 0);
        assertEquals(-.1, slide.z());
        assertEquals(wanted.y(), slide.y());
        assertEquals(new Vec(0, -.08, 0), SeparationStep.away(.9, 0, 1, wanted, step -> false));
    }
}
