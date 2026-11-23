package io.github.longshou.mcyukkuri.perception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PerceptionMathTest {
    @Test void sightRangeIsAnIntervalShiftedByIntelligenceAndNeed() {
        assertEquals(10, Sight.range(0, false), 1e-9);
        assertEquals(16, Sight.range(100, false), 1e-9);
        assertTrue(Sight.range(50, false) > 10 && Sight.range(50, false) < 16);
        assertEquals(12.5, Sight.range(0, true), 1e-9);
        assertEquals(20, Sight.range(100, true), 1e-9);
    }

    @Test void timidBodiesScanWiderAndPanicNarrowsTheCone() {
        assertTrue(Sight.coneCos(0, false) < Sight.coneCos(100, false), "timid cone must be wider (smaller cosine)");
        assertTrue(Sight.coneCos(0, false) < 0 && Sight.coneCos(100, false) > 0);
        assertTrue(Sight.coneCos(0, true) > Sight.coneCos(0, false), "panic narrows the cone");
    }

    @Test void inViewNeedsAwakeRangeAndFacingExceptVeryNear() {
        double range = 12, cos = Sight.coneCos(50, false);
        assertTrue(Sight.inView(true, 25, 1, range, cos));
        assertFalse(Sight.inView(false, 25, 1, range, cos), "asleep sees nothing");
        assertFalse(Sight.inView(true, 13 * 13, 1, range, cos), "beyond range");
        assertFalse(Sight.inView(true, 25, -1, range, cos), "directly behind");
        assertTrue(Sight.inView(true, 2.4 * 2.4, -1, range, cos), "very near is always seen");
        assertFalse(Sight.inView(true, 2.6 * 2.6, -1, range, cos), "just beyond the near zone is not");
    }

    @Test void aStillThingFacingAwayIsNotSeenButFacingItIs() {
        // The same rule serves events and polled fixtures: back turned at 8 blocks sees nothing, front facing sees it.
        double cos = Sight.coneCos(50, false);
        assertFalse(Sight.inView(true, 64, -.8, 14, cos));
        assertTrue(Sight.inView(true, 64, .9, 14, cos));
        assertTrue(Sight.inView(true, 64, 0, 14, cos), "side on is inside a half angle of ninety degrees");
        assertTrue(Sight.inView(true, 4, -.8, 14, cos), "within two blocks and a half it does not matter");
    }

    @Test void wallsHalveTheRadiusUpToThreeLayers() {
        double open = Hearing.radius(8, 50, false, false, false, 0);
        assertEquals(open / 2, Hearing.radius(8, 50, false, false, false, 1), 1e-9);
        assertEquals(open / 8, Hearing.radius(8, 50, false, false, false, 3), 1e-9);
        assertEquals(open / 8, Hearing.radius(8, 50, false, false, false, 9), 1e-9, "a thick wall is no more than three layers");
        assertEquals(open, Hearing.radius(8, 50, false, false, false, -2), 1e-9);
    }

    @Test void hearingShiftsWithStatsAndState() {
        double calm = Hearing.radius(8, 50, false, false, false, 0);
        assertTrue(Hearing.radius(8, 0, false, false, false, 0) > Hearing.radius(8, 100, false, false, false, 0), "timid hear farther");
        assertEquals(calm * .4, Hearing.radius(8, 50, false, true, false, 0), 1e-9);
        assertEquals(calm * .6, Hearing.radius(8, 50, false, false, true, 0), 1e-9);
        assertEquals(calm * 1.25, Hearing.radius(8, 50, true, false, false, 0), 1e-9);
    }

    @Test void bucketPourCarriesAboutSevenToTenBlocksInOpenAirAndLessThroughAWall() {
        assertEquals(7.2, Hearing.radius(Cue.POUR_BUCKET.loudness, 100, false, false, false, 0), .01);
        assertEquals(9.2, Hearing.radius(Cue.POUR_BUCKET.loudness, 0, false, false, false, 0), .01);
        assertTrue(Hearing.audible(Hearing.radius(8, 50, false, false, false, 0), 6 * 6));
        assertFalse(Hearing.audible(Hearing.radius(8, 50, false, false, false, 0), 12 * 12), "a pet a dozen blocks outside hears nothing");
        assertFalse(Hearing.audible(Hearing.radius(8, 50, false, false, false, 1), 6 * 6), "one wall puts six blocks out of reach");
    }

    @Test void emptyLookFadesFasterTheMoreUrgentTheNeed() {
        assertEquals(2400, Belief.decay(0));
        assertEquals(300, Belief.decay(1));
        assertTrue(Belief.decay(.5) < Belief.decay(.1));
        assertEquals(300, Belief.decay(5), "urgency beyond the scale does not shorten it further");
        assertEquals(0, Belief.waterUrgency(40), 1e-9);
        assertEquals(1, Belief.waterUrgency(95), 1e-9);
        assertEquals(0, Belief.foodUrgency(45), 1e-9);
        assertEquals(1, Belief.foodUrgency(5), 1e-9);
    }

    @Test void beliefStatesFollowTheLastLook() {
        assertEquals(Belief.State.UNKNOWN, Belief.state(null, 100, 600));
        assertEquals(Belief.State.FULL, Belief.state(new Belief.Entry(0, true), 100000, 600), "a stocked look is kept until the next look");
        assertEquals(Belief.State.EMPTY, Belief.state(new Belief.Entry(0, false), 599, 600));
        assertEquals(Belief.State.UNKNOWN, Belief.state(new Belief.Entry(0, false), 600, 600), "an empty look decays so the body checks again");
    }
}
