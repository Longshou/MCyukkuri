package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/** Bodies whose foot circles overlap on a host part: each pair moves apart along their centres, the lighter yielding more and, on a tilt, the lower one; fixed circles push alone. */
public final class Squeeze {
    /** Contact after moving the first footprint by (dx,dz), keeping its identity; the host supplies its supported height. */
    @FunctionalInterface public interface Contact { boolean test(Disc a, Disc b, double dx, double dz); }
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

    /** How far each body moves to part from the others, {@code {x, z, deepest}}: the move and the deepest overlap before moving; {@code downX, downZ}
     *  is the floor's downhill direction, so its lower body yields more. */
    public static double[][] separate(List<Body> bodies, List<Disc> fixed, double slack, double downX, double downZ) {
        return separate(bodies, fixed, slack, downX, downZ, (a,b,dx,dz) -> Disc.level(a.bottom(),a.top(),b.bottom(),b.top())
                && Math.hypot(a.x()+dx-b.x(),a.z()+dz-b.z()) < a.r()+b.r()-slack);
    }
    /** A shaped host supplies contact along the separation direction; circles bound the overlap search, abilities the speed. */
    public static double[][] separate(List<Body> bodies, List<Disc> fixed, double slack, double downX, double downZ,
                                      Contact contact) {
        int n = bodies.size();
        double[][] out = new double[n][3];
        for (int i = 0; i < n; i++) {
            var a = bodies.get(i);
            for (int j = i + 1; j < n; j++) {
                var b = bodies.get(j);
                if (!Disc.level(a.bottom(), a.top(), b.bottom(), b.top())) continue;
                double dx = b.x() - a.x(), dz = b.z() - a.z(), d = Math.hypot(dx, dz), depth = a.r() + b.r() - slack - d;
                if (depth <= TOUCH) continue;
                if (!contact.test(disc(a), disc(b), 0, 0)) continue;
                double angle = (i * n + j) * GOLDEN, nx = d > 1e-6 ? dx / d : Math.cos(angle), nz = d > 1e-6 ? dz / d : Math.sin(angle);
                depth = depth(disc(a), disc(b), depth, -nx, -nz, contact);
                out[i][2] = Math.max(out[i][2], depth);
                out[j][2] = Math.max(out[j][2], depth);
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
                if (!contact.test(disc(a), c, 0, 0)) continue;
                double down = Math.hypot(downX, downZ);
                double nx = d > 1e-6 ? dx / d : down > 1e-6 ? downX / down : 1, nz = d > 1e-6 ? dz / d : down > 1e-6 ? downZ / down : 0;
                depth = depth(disc(a), c, depth, nx, nz, contact);
                out[i][2] = Math.max(out[i][2], depth);
                out[i][0] += nx * Math.min(depth * GENTLE, a.step());
                out[i][1] += nz * Math.min(depth * GENTLE, a.step());
            }
        }
        for (int i = 0; i < n; i++) {
            // The summed shove cannot pass into a fixed obstacle that was clear before the move.
            var a = disc(bodies.get(i));
            for (var c : fixed) if (!contact.test(a,c,0,0) && contact.test(a,c,out[i][0],out[i][1])) {
                double inside=0,outside=1;
                for (int k=0;k<12;k++) {
                    double mid=(inside+outside)/2;
                    if (contact.test(a,c,out[i][0]*mid,out[i][1]*mid)) outside=mid; else inside=mid;
                }
                out[i][0]*=inside;out[i][1]*=inside;
            }
            double length = Math.hypot(out[i][0], out[i][1]), step = bodies.get(i).step();
            if (length > step) { out[i][0] *= step / length; out[i][1] *= step / length; }
        }
        return out;
    }
    /** The broad circle bounds the search, not the shove: shallow shaped contact must produce a shallow displacement. */
    private static double depth(Disc a, Disc b, double outside, double nx, double nz, Contact contact) {
        double inside = 0;
        for (int k = 0; k < 12; k++) {
            double mid = (inside + outside) / 2;
            if (contact.test(a, b, nx * mid, nz * mid)) inside = mid; else outside = mid;
        }
        return outside;
    }
    public static Disc disc(Body body) { return new Disc(body.x(),body.z(),body.r(),body.bottom(),body.top()); }
}
