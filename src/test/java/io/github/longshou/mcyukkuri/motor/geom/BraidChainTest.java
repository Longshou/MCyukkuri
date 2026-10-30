package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The braid chain bent to a point: lengths kept, root fixed, end on the point when in reach, smooth and limited. */
class BraidChainTest {
    /** Marisa's rest braid in model units: hand_right, hand_right_braid_1..5, hand_right_tip, then the tuft end. */
    private static final double[] REST = {6.9333, 9.328, -2.52, 7.52, 7.015, -3.3075, 7.52, 6.135, -3.3075, 7.52, 5.255, -3.3075,
            7.52, 4.375, -3.3075, 7.52, 3.495, -3.3075, 7.52, 2.222, -3.3075, 8.5098, .21227, -4.208};

    private static double segment(double[] p, int i) {
        return Math.sqrt(Math.pow(p[3 * i + 3] - p[3 * i], 2) + Math.pow(p[3 * i + 4] - p[3 * i + 1], 2) + Math.pow(p[3 * i + 5] - p[3 * i + 2], 2));
    }

    private static double angle(double[] p, int i) {
        double ax = p[3 * i] - p[3 * i - 3], ay = p[3 * i + 1] - p[3 * i - 2], az = p[3 * i + 2] - p[3 * i - 1];
        double bx = p[3 * i + 3] - p[3 * i], by = p[3 * i + 4] - p[3 * i + 1], bz = p[3 * i + 5] - p[3 * i + 2];
        return Math.acos(Math.clamp((ax * bx + ay * by + az * bz) / Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz)), -1, 1));
    }

    private static double length() { double s = 0; for (int i = 0; i < REST.length / 3 - 1; i++) s += segment(REST, i); return s; }

    private static void keepsShape(double[] out) {
        assertEquals(REST.length, out.length);
        for (int i = 0; i < REST.length / 3 - 1; i++) assertEquals(segment(REST, i), segment(out, i), 1e-6, "segment " + i + " keeps its length");
        assertArrayEquals(new double[]{REST[0], REST[1], REST[2]}, new double[]{out[0], out[1], out[2]}, 1e-12, "the root stays");
    }

    private static double endError(double[] out, double x, double y, double z) {
        int e = out.length - 3;
        return Math.sqrt(Math.pow(out[e] - x, 2) + Math.pow(out[e + 1] - y, 2) + Math.pow(out[e + 2] - z, 2));
    }

    @Test void reachesAPointOnASmoothArc() {
        double[][] targets = {{14, 4, -4}, {11, 6, -6}, {7, 3, -6}, {16, 9, -2}, {10, 2, -6}};
        for (var t : targets) {
            var out = BraidChain.reach(REST, t[0], t[1], t[2]);
            keepsShape(out);
            assertEquals(0, endError(out, t[0], t[1], t[2]), 1e-5, "the end lies on the point");
            for (int i = 1; i < REST.length / 3 - 1; i++) assertTrue(angle(out, i) <= BraidChain.MAX_TURN + 1e-6, "joint " + i + " stays inside its turn");
        }
    }

    @Test void bendsAlongTheWholeLength() {
        var out = BraidChain.reach(REST, 11, 6, -6);
        for (int i = 1; i < REST.length / 3 - 1; i++) assertTrue(angle(out, i) > Math.toRadians(3), "joint " + i + " takes part of the bend");
    }

    @Test void aPointOutOfReachGetsTheStraightBraidNeverStretched() {
        var out = BraidChain.reach(REST, 30, 0, -3);
        keepsShape(out);
        for (int i = 1; i < REST.length / 3 - 1; i++) assertEquals(0, angle(out, i), 1e-6);
        double reach = Math.sqrt(Math.pow(out[out.length - 3] - REST[0], 2) + Math.pow(out[out.length - 2] - REST[1], 2) + Math.pow(out[out.length - 1] - REST[2], 2));
        assertEquals(length(), reach, 1e-6, "the end stops at the braid's own length");
    }

    @Test void aNearPointFoldsTheBraidOntoIt() {
        var out = BraidChain.reach(REST, 9, 12, -3);
        keepsShape(out);
        assertEquals(0, endError(out, 9, 12, -3), 1e-3, "the end still closes on a point nearer than the arc's curl");
    }

    @Test void theRestTipIsTheRestBraid() {
        var out = BraidChain.reach(REST, REST[REST.length - 3], REST[REST.length - 2], REST[REST.length - 1]);
        keepsShape(out);
        assertEquals(0, endError(out, REST[REST.length - 3], REST[REST.length - 2], REST[REST.length - 1]), 1e-5);
    }

    @Test void theBulgeKeepsTheBraidsOwnSide() {
        // A braid already swung outward (+x) bends on that side when its end is drawn in.
        var bent = REST.clone();
        for (int i = 1; i < bent.length / 3; i++) bent[3 * i] += .4 * Math.sin(Math.PI * i / (bent.length / 3 - 1));
        var out = BraidChain.reach(bent, 8, 3, -4);
        double ux = 8 - out[0], uy = 3 - out[1], uz = -4 - out[2], u = Math.sqrt(ux * ux + uy * uy + uz * uz);
        double vx = out[9] - out[0], vy = out[10] - out[1], vz = out[11] - out[2], along = (vx * ux + vy * uy + vz * uz) / (u * u);
        assertTrue(vx - ux * along > 0, "the middle stays outward of the chord");
    }
}
