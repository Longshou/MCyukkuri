package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.family.BirthMotion;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Squeezing: who yields, by how much, and that fixed circles never move. */
class SqueezeTest {
    private static final double SLACK = .02;

    @Test void apartBodiesStayPut() {
        var bodies = List.of(new Squeeze.Body(0, 0, 1, 1, 9), new Squeeze.Body(3, 0, 1, 1, 9));
        assertTrue(!Squeeze.presses(0, 0, 1, 3, 0, 1, SLACK));
        for (var m : Squeeze.separate(bodies, List.of(), SLACK, 0, 0)) assertEquals(0, Math.hypot(m[0], m[1]), 1e-12);
        assertTrue(Squeeze.presses(0, 0, 1, 1.9, 0, 1, SLACK));
    }

    @Test void equalBodiesShareTheOverlap() {
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 1, 9), new Squeeze.Body(1, 0, 1, 1, 9)), List.of(), SLACK, 0, 0);
        double depth = (2 - SLACK - 1) / 4;
        assertEquals(-depth / 2, moves[0][0], 1e-12);
        assertEquals(depth / 2, moves[1][0], 1e-12);
        assertEquals(0, moves[0][1], 1e-12);
        assertEquals(depth * 4, moves[0][2], 1e-12, "the deepest overlap it is in");
    }

    @Test void theLighterYieldsMore() {
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 3, 9), new Squeeze.Body(1, 0, 1, 1, 9)), List.of(), SLACK, 0, 0);
        double depth = (2 - SLACK - 1) / 4;
        assertEquals(-depth / 4, moves[0][0], 1e-12);
        assertEquals(depth * 3 / 4, moves[1][0], 1e-12);
    }

    @Test void theLowerYieldsMoreOnATiltedFloor() {
        // downhill is +x: the second body lies below the first
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 1, 9), new Squeeze.Body(1, 0, 1, 1, 9)), List.of(), SLACK, .2, 0);
        assertTrue(moves[1][0] > -moves[0][0], "the lower body is pushed further than the upper one");
        assertEquals((2 - SLACK - 1) / 4, moves[1][0] - moves[0][0], 1e-12);
    }

    @Test void fixedCirclesNeverMoveAndPushABodyAlone() {
        var moves = Squeeze.separate(List.of(new Squeeze.Body(1, 0, 1, 1, 9)), List.of(new Disc(0, 0, 1)), SLACK, 0, 0);
        assertEquals((2 - SLACK - 1) / 4, moves[0][0], 1e-12);
    }

    @Test void shallowShapedContactDoesNotShoveAcrossTheGapToTheOppositeObstacle() {
        // A wide body's narrow underside is between two food portions. Its broad circles overlap deeply on both sides.
        var food = List.of(new Disc(-.4, 0, .1), new Disc(.4, 0, .1));
        double x = .025;
        for (int tick = 0; tick < 100; tick++) {
            var move = Squeeze.separate(List.of(new Squeeze.Body(x, 0, .8, 1, .1)), food, 0, 0, 0,
                    (a,b,dx,dz) -> Math.hypot(a.x()+dx-b.x(),a.z()+dz-b.z()) < .38)[0];
            assertTrue(move[0] <= 0, "no alternating shove from the opposite portion");
            assertTrue(x + move[0] >= .019, "stop at the real contact, not the broad circle");
            x += move[0];
        }
        assertEquals(.02, x, .001);
    }

    @Test void separationStopsAtAPreviouslyClearFixedObstacle() {
        var move=Squeeze.separate(List.of(new Squeeze.Body(0,0,1,1,1)),
                List.of(new Disc(-1.5,0,1),new Disc(1.15,0,.1)),SLACK,0,0)[0];
        assertEquals(.07,move[0],.0001,"the left obstacle pushes right only as far as the right obstacle permits");
        assertEquals(0,move[1]);
    }

    @Test void shallowContactKeepsPreBasinFixPressureAndTwentyTickSqueezeOff() {
        var rider = new Squeeze.Body(0,0,.8,1,.1);
        var neighbour = new Squeeze.Body(1,0,.8,1,.1);
        var food = new Disc(.4,0,.1);
        int fixedTicks = 0, pairTicks = 0;
        // A rider held at the rim cannot move: before b6b517061 the pressure depth was the circle overlap.
        for (int tick=1; tick<=20; tick++) {
            var fixed = Squeeze.separate(List.of(rider),List.of(food),0,0,0,
                    (a,b,dx,dz)->Math.hypot(a.x()+dx-b.x(),a.z()+dz-b.z())<.405)[0];
            var pair = Squeeze.separate(List.of(rider,neighbour),List.of(),0,0,0,
                    (a,b,dx,dz)->Math.hypot(a.x()+dx-b.x(),a.z()+dz-b.z())<1.005);
            assertEquals(rider.r()+food.r()-.4,fixed[2],1e-12);
            assertEquals(rider.r()+neighbour.r()-1,pair[0][2],1e-12);
            assertEquals(pair[0][2],pair[1][2]);
            assertTrue(Math.abs(fixed[0])<.002 && Math.abs(pair[0][0])<.001);
            if (fixed[2]>.1*rider.r()) fixedTicks++;
            if (pair[0][2]>.1*rider.r()) pairTicks++;
            assertEquals(tick==20,fixedTicks>=20);
            assertEquals(tick==20,pairTicks>=20);
        }
    }

    @Test void supportedZeroBaselineDoesNotInventANewObstacleFromStaleHeight() {
        var fixed = List.of(new Disc(-1.5,0,1,0,2),new Disc(1,0,.1,0,2));
        Squeeze.Contact supported = (a,b,dx,dz) -> {
            double y = 0; // Current support, including dx=dz=0; the stored bottom is .8.
            return b.x()<0 ? a.x()+dx<.48 : y<.5 && a.x()+dx>-.01;
        };
        var move = Squeeze.separate(List.of(new Squeeze.Body(0,0,1,1,1,.8,1.8)),fixed,SLACK,0,0,supported)[0];
        assertTrue(move[0]>.1,"the already touching right obstacle must not zero the left shove");
        assertEquals(0,move[3]);
    }

    @Test void clampSkipsUnreachableDiscsAndZeroShoves() {
        Squeeze.separate(List.of(new Squeeze.Body(0,0,1,1,1,0,1)),
                List.of(new Disc(20,0,1,0,1),new Disc(0,0,1,3,4)),0,0,0,
                (a,b,dx,dz)->{throw new AssertionError("broad phase must reject this disc");});
        int[] calls = {0};
        Squeeze.separate(List.of(new Squeeze.Body(0,0,1,1,0)),List.of(new Disc(1,0,1)),0,0,0,
                (a,b,dx,dz)->{calls[0]++;return Math.abs(a.x()+dx-b.x())<2;});
        assertEquals(13,calls[0],"one baseline and twelve depth tests; no clamp work for zero displacement");
    }

    @Test void zeroedShoveIsReportedWithoutChangingPressure() {
        var move = Squeeze.separate(List.of(new Squeeze.Body(0,0,1,1,1)),
                List.of(new Disc(-1.5,0,1),new Disc(1.08000001,0,.1)),SLACK,0,0)[0];
        assertEquals(0,move[0]);
        assertEquals(.48,move[2],1e-12);
        assertEquals(1,move[3]);
    }

    @Test void firstSeenRiderInitializesMotionAndRealSlidesStillHoldLabor() {
        var motion = new BirthMotion();
        var host = new Object();
        var at = new Vec(2,.5,-3);
        assertEquals(0,motion.sample(host,null));
        assertEquals(0,motion.sample(host,at));
        assertEquals(0,motion.sample(host,at));
        assertTrue(motion.sample(host,at.add(new Vec(.03,0,0)))>.0004);
        assertEquals(0,motion.sample(host,null));
        assertEquals(0,motion.sample(host,at));
        assertEquals(0,motion.sample(null,new Vec(100,60,100)),"dismount changes the coordinate frame");
        assertTrue(motion.sample(null,new Vec(100,60.03,100))>.0004);
        assertEquals(0,motion.sample(new Object(),at));
    }

    @Test void aCappedYielderKeepsThePairsMassRatio() {
        // the light body may move .1 a tick: the pair parts by .1/.75 and the heavy one moves a third of the light one's step
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 3, 9), new Squeeze.Body(.2, 0, 1, 1, .1)), List.of(), SLACK, 0, 0);
        assertEquals(.1, moves[1][0], 1e-12);
        assertEquals(-.1 / 3, moves[0][0], 1e-12);
    }

    @Test void stackedBodiesPartAlongDistinctAxes() {
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 1, 9), new Squeeze.Body(0, 0, 1, 1, 9), new Squeeze.Body(0, 0, 1, 1, 9)),
                List.of(), SLACK, 0, 0);
        for (var m : moves) assertTrue(Math.hypot(m[0], m[1]) > .1);
        assertTrue(Math.abs(moves[0][0] - moves[1][0]) + Math.abs(moves[0][1] - moves[1][1]) > .1);
    }
}
