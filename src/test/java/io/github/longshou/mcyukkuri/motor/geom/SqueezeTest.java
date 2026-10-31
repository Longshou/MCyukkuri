package io.github.longshou.mcyukkuri.motor.geom;

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
