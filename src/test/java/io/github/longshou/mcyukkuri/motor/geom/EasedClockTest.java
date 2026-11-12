package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasedClockTest {
    private static final int FRAMES = 3;

    /** Drives the clock like the client: one tick per local tick, the remote time re-set to {@code server} on re-sync ticks;
     *  returns the largest backward and forward step between consecutive drawn frames (FRAMES per tick). */
    private static double[] run(EasedClock clock, long[] remote, double[] last) {
        double back = 0, ahead = 0;
        for (long target : remote) {
            clock.step(target, true);
            for (int f = 0; f < FRAMES; f++) {
                double at = clock.at(f / (float) FRAMES);
                if (!Double.isNaN(last[0])) {
                    back = Math.max(back, last[0] - at);
                    ahead = Math.max(ahead, at - last[0]);
                }
                last[0] = at;
            }
        }
        return new double[]{back, ahead};
    }

    /** The client's own copy: +1 per tick from {@code start}, set to {@code resync} at tick {@code at}. */
    private static long[] copy(long start, int ticks, int at, long resync) {
        var out = new long[ticks];
        long time = start;
        for (int i = 0; i < ticks; i++) {
            time = i == at ? resync : time + 1;
            out[i] = time;
        }
        return out;
    }

    @Test void inStepItIsTheRemoteTime() {
        var clock = new EasedClock(99);
        for (long t = 100; t < 140; t++) {
            clock.step(t, true);
            assertEquals(t, clock.at(0), 1e-9);
            assertEquals(t + .5, clock.at(.5f), 1e-9);
        }
    }

    @Test void aReSyncBackNeverRunsBackwardAndIsClosedWithinTenTicks() {
        // A server running slower than the client: the client's copy is set back by 4 ticks.
        var raw = copy(1000, 19, 10, 1011 - 4);
        assertTrue(raw[10] < raw[9]);
        var clock = new EasedClock(1000);
        var last = new double[]{Double.NaN};
        var steps = run(clock, raw, last);
        assertEquals(0, steps[0], 1e-9);
        assertTrue(steps[1] <= (1 + EasedClock.EASE) / FRAMES + 1e-6, "fastest frame " + steps[1]);
        assertEquals(raw[raw.length - 1], clock.at(0), 1e-9);
    }

    @Test void aReSyncAheadIsClosedWithoutSkipping() {
        // A server catching up: the client's copy is set forward by 5 ticks.
        var raw = copy(1000, 21, 10, 1011 + 5);
        var clock = new EasedClock(1000);
        var steps = run(clock, raw, new double[]{Double.NaN});
        assertEquals(0, steps[0], 1e-9);
        assertTrue(steps[1] <= (1 + EasedClock.EASE) / FRAMES + 1e-6, "fastest frame " + steps[1]);
        assertEquals(raw[raw.length - 1], clock.at(0), 1e-9);
    }

    @Test void pausedItHoldsTheCurrentFractionalFrame() {
        var clock = new EasedClock(500);
        clock.step(503, true);
        double held = clock.at(.25f), next = clock.at(1);
        for (int i = 0; i < 10; i++) {
            clock.step(503, false);
            assertEquals(held, clock.at(.25f), 1e-9);
        }
        clock.step(504, true);
        assertEquals(next, clock.at(0), 1e-9);
    }

    @Test void largeCorrectionsStillPreserveContinuityAndConverge() {
        for (int correction : new int[]{-30, 30}) {
            var raw = copy(1000, 80, 10, 1011 + correction);
            var clock = new EasedClock(1000);
            var steps = run(clock, raw, new double[]{Double.NaN});
            assertEquals(0, steps[0], 1e-9);
            assertTrue(steps[1] <= (1 + EasedClock.EASE) / FRAMES + 1e-6, "fastest frame " + steps[1]);
            assertEquals(raw[raw.length - 1], clock.at(0), 1e-9);
        }
    }
}
