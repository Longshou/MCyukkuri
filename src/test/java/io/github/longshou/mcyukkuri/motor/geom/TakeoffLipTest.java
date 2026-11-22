package io.github.longshou.mcyukkuri.motor.geom;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TakeoffLipTest {
    /** A bank block whose top is y=0, beside a stand at y=-.08 for a body {@code width} wide centred at x. */
    private static boolean sunk(double x, double width, double feetY) { return x + width / 2 > 0 && feetY < 0; }

    @Test void shallowSunkStartIsLiftedOutOfTheBlock() {
        double width = .8, x = -.3, feet = -.08;
        assertTrue(sunk(x, width, feet), "premise: the start box overlaps the bank block");
        assertEquals(.08, TakeoffLip.lift(l -> !sunk(x, width, feet + l)), 1e-6);
    }

    @Test void clearStartNeedsNoLiftAndADeepOneIsStillRefused() {
        assertEquals(0, TakeoffLip.lift(l -> true));
        assertEquals(-1, TakeoffLip.lift(l -> !sunk(-.3, .8, -.5 + l)), "a stand half a block under the bank is a wall, not a lip");
    }

    @Test void theLipFallsAwayAsTheBodyRises() {
        assertEquals(.08, TakeoffLip.held(.08, 0), 1e-9);
        assertEquals(.03, TakeoffLip.held(.08, .05), 1e-9);
        assertEquals(0, TakeoffLip.held(.08, .4), 1e-9);
    }

    @Test void aSunkStartThatAWallBlocksFromTheSideStillFailsTheSolver() {
        double width = .8, x = -.3, feet = -.08;
        double lip = TakeoffLip.lift(l -> !sunk(x, width, feet + l));
        var from = new Vec(x, feet, 0);
        assertInstanceOf(ArcSolver.Blocked.class, ArcSolver.lowest(from, new Vec(.5, 0, 0), .8, 1.2, 1, points -> {
            for (var p : points) if (sunk(x + p.x(), width, feet + p.y())) return false;
            return true;
        }), "without the lip the sunk start blocks every arc");
        // the hop clears the bank top only once the sunk edge has risen out of the block (the lifted sweep), and the body is clear after
        var plan = ArcSolver.lowest(from, new Vec(.5, 0, 0), .8, 1.2, 1, points -> {
            for (var p : points) if (sunk(x + p.x(), width, feet + p.y() + TakeoffLip.held(lip, p.y()))) return false;
            return true;
        });
        assertInstanceOf(ArcSolver.Plan.class, plan);
    }

    /** The logged refusal: a hat stop 0.56 away and 0.25 up lies 0.08 under the bank top, its box 0.1 over the bank block's edge. */
    private static boolean bank(double s, double y) { return s + .4 > .86 && y < .33; }

    @Test void aHostStandSunkIntoTheBankIsLandedOnThroughItsLip() {
        var from = new Vec(0, 0, 0);
        var stop = new Vec(.56, .25, 0);
        double lip = TakeoffLip.lift(l -> !bank(stop.x(), stop.y() + l));
        assertEquals(.08, lip, 1e-6);
        assertInstanceOf(ArcSolver.Blocked.class, ArcSolver.lowest(from, stop, .72, .9, 1, points -> {
            for (var p : points) if (bank(p.x(), p.y())) return false;
            return true;
        }), "without the landing lip the last of the fall is in the block");
        var plan = ArcSolver.lowest(from, stop, .72, .9, 1, points -> {
            double prev = Double.MAX_VALUE;
            for (var p : points) {
                boolean falling = p.y() < prev;
                if (bank(p.x(), p.y() + TakeoffLip.lifted(0, p.y(), lip, p.y() - stop.y(), falling))) return false;
                prev = p.y();
            }
            return true;
        });
        assertInstanceOf(ArcSolver.Plan.class, plan);
    }

    @Test void aRiseIsNeverHeldUpByTheLandingLipAndADeepStandStillBlocks() {
        assertEquals(0, TakeoffLip.lifted(0, .1, .08, 0, false), 1e-9);
        assertEquals(.08, TakeoffLip.lifted(0, .1, .08, 0, true), 1e-9);
        assertEquals(.03, TakeoffLip.lifted(0, .1, .08, .05, true), 1e-9);
        assertEquals(.08, TakeoffLip.lifted(.08, 0, .03, 0, true), 1e-9, "the larger of the two lips holds");
        assertEquals(-1, TakeoffLip.lift(l -> !bank(.56, .0 + l)), "a stand 0.33 under the bank is a wall");
    }
}
