package io.github.longshou.mcyukkuri.motor.geom;

/**
 * Marisa's braid as a chain of rigid segments bent so its end lies on a point; pure maths, the authored clips use the
 * same solve (standards/yukkuri-library/motions/variants/contact-safe/braid_chain.py). Joints are flat x, y, z triples,
 * root first. The braid takes a circular arc that keeps every segment's length, turns no joint more than
 * {@link #MAX_TURN} and bulges to its own side, a little below the chord and away from the body's vertical axis
 * (model x = z = 0); nearer than that curl allows,
 * the curl folds further until the end is on the point; out of reach it points straight at it, never stretched.
 */
public final class BraidChain {
    /** Largest turn between two neighbouring segments on the arc, radians (project design). */
    public static final double MAX_TURN = Math.toRadians(40);

    private BraidChain() {}

    /** The joints bent toward the target; the root stays, every segment keeps its length. */
    public static double[] reach(double[] joints, double tx, double ty, double tz) {
        int n = joints.length / 3 - 1;
        double[] out = joints.clone(), seg = new double[n];
        double length = 0, longest = 0, shortest = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            seg[i] = dist(joints, i, joints, i + 1); length += seg[i];
            longest = Math.max(longest, seg[i]); shortest = Math.min(shortest, seg[i]);
        }
        double rx = joints[0], ry = joints[1], rz = joints[2], ux = tx - rx, uy = ty - ry, uz = tz - rz;
        double d = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (n == 0 || d < 1e-6 || shortest < 1e-9) return out;
        ux /= d; uy /= d; uz /= d;
        if (d >= length) {
            double s = 0;
            for (int i = 0; i < n; i++) { s += seg[i]; set(out, i + 1, rx + ux * s, ry + uy * s, rz + uz * s); }
            return out;
        }
        // Bend plane: the joints' own bulge off the chord, plus a little sag and a lean away from the body's axis, so a
        // straight braid droops and curves round the head and brim rather than into them.
        double axis = Math.max(1e-9, Math.sqrt(rx * rx + rz * rz)), px = .5 * rx / axis, py = -.3, pz = .5 * rz / axis;
        for (int i = 1; i < n; i++) { px += joints[3 * i] - rx; py += joints[3 * i + 1] - ry; pz += joints[3 * i + 2] - rz; }
        double along = px * ux + py * uy + pz * uz; px -= ux * along; py -= uy * along; pz -= uz * along;
        double norm = Math.sqrt(px * px + py * py + pz * pz);
        if (norm < 1e-6) {
            px = Math.abs(ux) < .9 ? 1 : 0; py = 0; pz = Math.abs(ux) < .9 ? 0 : 1;
            along = px * ux + pz * uz; px -= ux * along; py -= uy * along; pz -= uz * along;
            norm = Math.sqrt(px * px + py * py + pz * pz);
        }
        px /= norm; py /= norm; pz /= norm;
        double low = 0, high = 1.999 / longest;
        for (int i = 0; i < 50; i++) {
            double k = (low + high) / 2;
            if (turn(k, seg) <= MAX_TURN && total(k, seg) <= 1.9 * Math.PI) low = k; else high = k;
        }
        double k = low;
        boolean folded = chord(k, seg, length) > d;
        if (!folded) {
            double a = 0, b = k;
            for (int i = 0; i < 60; i++) { double m = (a + b) / 2; if (chord(m, seg, length) > d) a = m; else b = m; }
            k = (a + b) / 2;
        }
        if (k > 1e-9) {
            double whole = total(k, seg), c = Math.cos(whole / 2), s = Math.sin(whole / 2), phi = 0;
            double t0x = c * ux + s * px, t0y = c * uy + s * py, t0z = c * uz + s * pz;
            double n0x = s * ux - c * px, n0y = s * uy - c * py, n0z = s * uz - c * pz;
            for (int i = 0; i < n; i++) {
                phi += central(k, seg[i]);
                double a = Math.sin(phi) / k, b = (1 - Math.cos(phi)) / k;
                set(out, i + 1, rx + a * t0x + b * n0x, ry + a * t0y + b * n0y, rz + a * t0z + b * n0z);
            }
        }
        if (folded) fold(out, seg, tx, ty, tz);
        return out;
    }

    /** FABRIK from the tightest arc: the end closes on a point nearer than the arc's own curl reaches. */
    private static void fold(double[] p, double[] seg, double tx, double ty, double tz) {
        int n = seg.length;
        double rx = p[0], ry = p[1], rz = p[2];
        for (int pass = 0; pass < 32; pass++) {
            set(p, n, tx, ty, tz);
            for (int i = n - 1; i >= 0; i--) toward(p, i, i + 1, seg[i]);
            set(p, 0, rx, ry, rz);
            for (int i = 0; i < n; i++) toward(p, i + 1, i, seg[i]);
            double ex = p[3 * n] - tx, ey = p[3 * n + 1] - ty, ez = p[3 * n + 2] - tz;
            if (ex * ex + ey * ey + ez * ez < 1e-8) return;
        }
    }

    /** Puts joint {@code moved} at {@code length} from joint {@code fixed}, on its present line. */
    private static void toward(double[] p, int moved, int fixed, double length) {
        double d = dist(p, moved, p, fixed);
        if (d < 1e-12) return;
        double q = length / d;
        set(p, moved, p[3 * fixed] + (p[3 * moved] - p[3 * fixed]) * q, p[3 * fixed + 1] + (p[3 * moved + 1] - p[3 * fixed + 1]) * q,
                p[3 * fixed + 2] + (p[3 * moved + 2] - p[3 * fixed + 2]) * q);
    }

    private static double central(double k, double seg) { return 2 * Math.asin(Math.min(1, k * seg / 2)); }

    private static double total(double k, double[] seg) { double s = 0; for (double l : seg) s += central(k, l); return s; }

    private static double turn(double k, double[] seg) {
        double most = 0;
        for (int i = 1; i < seg.length; i++) most = Math.max(most, (central(k, seg[i - 1]) + central(k, seg[i])) / 2);
        return most;
    }

    private static double chord(double k, double[] seg, double length) { return k > 1e-9 ? 2 * Math.sin(total(k, seg) / 2) / k : length; }

    private static double dist(double[] a, int i, double[] b, int j) {
        double x = a[3 * i] - b[3 * j], y = a[3 * i + 1] - b[3 * j + 1], z = a[3 * i + 2] - b[3 * j + 2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void set(double[] p, int i, double x, double y, double z) { p[3 * i] = x; p[3 * i + 1] = y; p[3 * i + 2] = z; }
}
