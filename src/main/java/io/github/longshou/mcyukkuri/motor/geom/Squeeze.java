package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/**
 * Bodies whose foot circles overlap on a host part (design Q6, refactor WP4b): each overlapping pair moves apart along the line
 * of their centres by a quarter of the overlap a tick (a crowd eases apart, it is not thrown), the lighter body yielding more and, on a tilted floor, the lower one more than the upper;
 * fixed circles (what the hollow holds) never move and push a body out alone. A body moves at most its own step a tick, and
 * a pair parts only as fast as its slower yielder allows, so the two keep their mass ratio. Only footprints that share some height
 * press on each other ({@link Disc#level}): a body standing high over another's top does not. Plane units are the caller's; no
 * Minecraft types.
 */
public final class Squeeze {
    /** Stacked centres part along a distinct axis per pair (the golden angle, radians). */
    private static final double GOLDEN = 2.399963;
    /** An overlap this small (plane units) is a touch, not a press: nothing moves for it. */
    private static final double TOUCH = 1e-3;
    /** The share of an overlap undone in one tick. */
    private static final double GENTLE = .25;

    /** One body: centre, foot radius, mass, the most it moves in one tick, and the heights it takes (feet to top). */
    public record Body(double x, double z, double r, double mass, double step, double bottom, double top) {
        /** A body that takes every height. */
        public Body(double x, double z, double r, double mass, double step) {
            this(x, z, r, mass, step, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
    }

    private Squeeze() {}

    /** Whether two circles press on each other (more than a touch): the test {@link #separate} parts them by. */
    public static boolean presses(double ax, double az, double ar, double bx, double bz, double br, double slack) {
        return ar + br - slack - Math.hypot(bx - ax, bz - az) > TOUCH;
    }

    /**
     * How far each body moves to part from the others, {@code {x, z, deepest}} per body: the move (zero for one that overlaps
     * nothing) and the deepest overlap it is in before moving. Circles overlap when their centres are nearer than {@code r1 + r2 - slack}. {@code downX, downZ} is the floor's downhill
     * direction scaled by the sine of its tilt (zero on a level floor): the pair's lower body yields that much more.
     */
    public static double[][] separate(List<Body> bodies, List<Disc> fixed, double slack, double downX, double downZ) {
        int n = bodies.size();
        double[][] out = new double[n][3];
        for (int i = 0; i < n; i++) {
            var a = bodies.get(i);
            for (int j = i + 1; j < n; j++) {
                var b = bodies.get(j);
                if (!Disc.level(a.bottom(), a.top(), b.bottom(), b.top())) continue;
                double dx = b.x() - a.x(), dz = b.z() - a.z(), d = Math.hypot(dx, dz), depth = a.r() + b.r() - slack - d;
                if (depth <= TOUCH) continue;
                out[i][2] = Math.max(out[i][2], depth);
                out[j][2] = Math.max(out[j][2], depth);
                double angle = (i * n + j) * GOLDEN, nx = d > 1e-6 ? dx / d : Math.cos(angle), nz = d > 1e-6 ? dz / d : Math.sin(angle);
                // b lies downhill of a when the normal points down the slope: a presses on it from above
                double down = nx * downX + nz * downZ, ma = a.mass() * (1 + down), mb = b.mass() * (1 - down), total = ma + mb;
                double yieldA = total <= 0 ? .5 : mb / total, apart = depth * GENTLE;
                if (yieldA > 0) apart = Math.min(apart, a.step() / yieldA);
                if (yieldA < 1) apart = Math.min(apart, b.step() / (1 - yieldA));
                out[i][0] -= nx * apart * yieldA;
                out[i][1] -= nz * apart * yieldA;
                out[j][0] += nx * apart * (1 - yieldA);
                out[j][1] += nz * apart * (1 - yieldA);
            }
            for (var c : fixed) {
                if (!Disc.level(a.bottom(), a.top(), c.bottom(), c.top())) continue;
                double dx = a.x() - c.x(), dz = a.z() - c.z(), d = Math.hypot(dx, dz), depth = a.r() + c.r() - slack - d;
                if (depth <= TOUCH) continue;
                out[i][2] = Math.max(out[i][2], depth);
                double down = Math.hypot(downX, downZ);
                double nx = d > 1e-6 ? dx / d : down > 1e-6 ? downX / down : 1, nz = d > 1e-6 ? dz / d : down > 1e-6 ? downZ / down : 0;
                out[i][0] += nx * Math.min(depth * GENTLE, a.step());
                out[i][1] += nz * Math.min(depth * GENTLE, a.step());
            }
        }
        for (int i = 0; i < n; i++) {
            double length = Math.hypot(out[i][0], out[i][1]), step = bodies.get(i).step();
            if (length > step) { out[i][0] *= step / length; out[i][1] *= step / length; }
        }
        return out;
    }
}
