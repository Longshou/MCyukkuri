package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q6 squeezing: who yields, by how much, and that fixed circles never move. */
class SqueezeTest {
    private static final double SLACK = .02;

    @Test void apartBodiesStayPut() {
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 1, 9), new Squeeze.Body(3, 0, 1, 1, 9)), List.of(), SLACK, 0, 0);
        for (var m : moves) assertEquals(0, Math.hypot(m[0], m[1]), 1e-12);
    }

    @Test void equalBodiesShareTheOverlap() {
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 1, 9), new Squeeze.Body(1, 0, 1, 1, 9)), List.of(), SLACK, 0, 0);
        double depth = 2 - SLACK - 1;
        assertEquals(-depth / 2, moves[0][0], 1e-12);
        assertEquals(depth / 2, moves[1][0], 1e-12);
        assertEquals(0, moves[0][1], 1e-12);
    }

    @Test void theLighterYieldsMore() {
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 3, 9), new Squeeze.Body(1, 0, 1, 1, 9)), List.of(), SLACK, 0, 0);
        double depth = 2 - SLACK - 1;
        assertEquals(-depth / 4, moves[0][0], 1e-12);
        assertEquals(depth * 3 / 4, moves[1][0], 1e-12);
    }

    @Test void theLowerYieldsMoreOnATiltedFloor() {
        // downhill is +x: the second body lies below the first
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 1, 9), new Squeeze.Body(1, 0, 1, 1, 9)), List.of(), SLACK, .2, 0);
        assertTrue(moves[1][0] > -moves[0][0], "the lower body is pushed further than the upper one");
        assertEquals(2 - SLACK - 1, moves[1][0] - moves[0][0], 1e-12);
    }

    @Test void fixedCirclesNeverMoveAndPushABodyAlone() {
        var moves = Squeeze.separate(List.of(new Squeeze.Body(1, 0, 1, 1, 9)), List.of(new Disc(0, 0, 1)), SLACK, 0, 0);
        assertEquals(2 - SLACK - 1, moves[0][0], 1e-12);
    }

    @Test void aCappedYielderKeepsThePairsMassRatio() {
        // the light body may move .1 a tick: the pair parts by .1/.75 and the heavy one moves a third of the light one's step
        var moves = Squeeze.separate(List.of(new Squeeze.Body(0, 0, 1, 3, 9), new Squeeze.Body(1, 0, 1, 1, .1)), List.of(), SLACK, 0, 0);
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
