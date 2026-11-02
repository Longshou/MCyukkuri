package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Walking round flat footprints (a placed hat's brim, a standing body's box): a body heading for a point steers for the
 * silhouette corner of the first footprint its straight line crosses, on the shorter side, with its own foot radius clear,
 * and keeps the side it took until the other is clearly shorter; a point it may not stand on moves to the nearest one clear.
 */
public final class Detour {
    /** How much shorter (blocks) the other side must be before a body leaves the side it took. */
    public static final double KEEP = .5;
    /** How far (blocks, along both axes) past a corner a body aims, and past the clearance a moved target lies. */
    public static final double PAST = .05;
    private static final double EPS = 1e-3;
    /** Footprints followed in a row when the way to one corner crosses another. */
    private static final int DEPTH = 3;

    private Detour() {}

    /** A footprint: centre, half extents along its axis (ux, uz) and across it, and whose it is. */
    public record Box(long id, double x, double z, double a, double b, double ux, double uz) {
        public static Box aligned(long id, double minX, double minZ, double maxX, double maxZ) {
            return new Box(id, (minX + maxX) / 2, (minZ + maxZ) / 2, (maxX - minX) / 2, (maxZ - minZ) / 2, 1, 0);
        }
        private double u(double px, double pz) { return (px - x) * ux + (pz - z) * uz; }
        private double v(double px, double pz) { return (pz - z) * ux - (px - x) * uz; }
        private double[] at(double u, double v) { return new double[]{x + u * ux - v * uz, z + u * uz + v * ux}; }
        /** How far a point lies outside, square-wise (the larger axis distance), negative inside: a body of foot radius r clears it at gap r. */
        public double gap(double px, double pz) { return Math.max(Math.abs(u(px, pz)) - a, Math.abs(v(px, pz)) - b); }
        /** Where along from-to (0 to 1) the segment enters it grown by c; NaN when it misses or only touches. */
        double enter(double fx, double fz, double tx, double tz, double c) {
            double pu = u(fx, fz), pv = v(fx, fz), du = u(tx, tz) - pu, dv = v(tx, tz) - pv, lo = 0, hi = 1;
            for (int axis = 0; axis < 2; axis++) {
                double p = axis == 0 ? pu : pv, d = axis == 0 ? du : dv, half = (axis == 0 ? a : b) + c;
                if (Math.abs(d) < 1e-12) { if (Math.abs(p) >= half) return Double.NaN; continue; }
                double t0 = (-half - p) / d, t1 = (half - p) / d;
                lo = Math.max(lo, Math.min(t0, t1));
                hi = Math.min(hi, Math.max(t0, t1));
                if (lo >= hi) return Double.NaN;
            }
            return lo;
        }
    }

    /** The point to head for now, the footprint and side (+1, -1) it turns round (side 0 heads straight for the target), the
     *  length of that way to the target, and {@code shut}: no corner of the footprint in the way is in sight or open (neighbours
     *  overlap it), so there is no way round to steer by and a planned route must find one. */
    public record Turn(double x, double z, long id, int side, double way, boolean shut) {
        static Turn straight(double fx, double fz, double tx, double tz) { return new Turn(tx, tz, 0, 0, Math.hypot(tx - fx, tz - fz), false); }
    }

    /**
     * From (fx, fz) toward (tx, tz) with foot radius {@code clear}. A footprint is kept no clearer than the body and the target
     * now leave it, so a body pressed close or a target beside it is still reached; one either of them is inside is left to
     * collision. {@code keepId}, {@code keepSide}: the footprint and side taken last.
     */
    public static Turn steer(double fx, double fz, double tx, double tz, double clear, List<Box> boxes, long keepId, int keepSide) {
        return steer(fx, fz, tx, tz, clear, boxes, keepId, keepSide, DEPTH);
    }

    private static Turn steer(double fx, double fz, double tx, double tz, double clear, List<Box> boxes, long keepId, int keepSide, int depth) {
        Box hit = null;
        double first = Double.POSITIVE_INFINITY;
        for (var b : boxes) {
            double c = room(b, fx, fz, tx, tz, clear), t = c < 0 ? Double.NaN : b.enter(fx, fz, tx, tz, c);
            if (t < first) { first = t; hit = b; }
        }
        if (hit == null || depth == 0) return Turn.straight(fx, fz, tx, tz);
        // corners (anticlockwise in its own axes) as far out as the body now is, up to its foot radius, and a little past
        double c = Math.max(room(hit, fx, fz, tx, tz, clear), Math.min(clear, hit.gap(fx, fz) - EPS)), dx = tx - fx, dz = tz - fz;
        double[][] k = new double[4][];
        int[] seen = new int[4];  // 0 hidden from the body, 1 in sight, 2 standing at it
        int left = -1, right = -1;
        double most = Double.NEGATIVE_INFINITY, least = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            k[i] = hit.at((i == 0 || i == 3 ? 1 : -1) * (hit.a + c + PAST), (i < 2 ? 1 : -1) * (hit.b + c + PAST));
            double kx = k[i][0] - fx, kz = k[i][1] - fz;
            seen[i] = Math.hypot(kx, kz) < PAST / 2 ? 2 : Double.isNaN(hit.enter(fx, fz, k[i][0], k[i][1], c)) ? 1 : 0;
            if (seen[i] != 1) continue;
            double angle = Math.atan2(dx * kz - dz * kx, dx * kx + dz * kz);
            if (angle > most) { most = angle; left = i; }
            if (angle < least) { least = angle; right = i; }
        }
        if (left < 0) return new Turn(tx, tz, hit.id(), 0, Math.hypot(tx - fx, tz - fz), true);
        double toTarget = room(hit, fx, fz, tx, tz, clear);
        double viaLeft = around(hit, k, seen, left, fx, fz, tx, tz, toTarget), viaRight = around(hit, k, seen, right, fx, fz, tx, tz, toTarget);
        int side = viaLeft <= viaRight ? 1 : -1;
        if (keepId == hit.id() && keepSide != 0 && (keepSide > 0 ? viaLeft : viaRight) <= Math.min(viaLeft, viaRight) + KEEP) side = keepSide;
        // a corner inside a neighbour is closed; with both closed there is no way round in sight
        boolean openLeft = open(k[left], boxes, hit, fx, fz, tx, tz, clear), openRight = open(k[right], boxes, hit, fx, fz, tx, tz, clear);
        if (!openLeft && !openRight) return new Turn(tx, tz, hit.id(), 0, Math.hypot(tx - fx, tz - fz), true);
        if (!(side > 0 ? openLeft : openRight)) side = -side;
        var aim = k[side > 0 ? left : right];
        var next = steer(fx, fz, aim[0], aim[1], clear, boxes, keepId, keepSide, depth - 1);
        return new Turn(next.x(), next.z(), hit.id(), side, side > 0 ? viaLeft : viaRight, next.shut());
    }

    /** The way round on one side: to its corner in sight, then corner to corner away from the body until the target shows. */
    private static double around(Box hit, double[][] k, int[] seen, int i, double fx, double fz, double tx, double tz, double c) {
        double length = Math.hypot(k[i][0] - fx, k[i][1] - fz);
        int step = seen[(i + 1) % 4] == 0 ? 1 : 3;
        for (int n = 0; n < 3 && !Double.isNaN(hit.enter(k[i][0], k[i][1], tx, tz, c)); n++) {
            int j = (i + step) % 4;
            length += Math.hypot(k[j][0] - k[i][0], k[j][1] - k[i][1]);
            i = j;
        }
        return length + Math.hypot(tx - k[i][0], tz - k[i][1]);
    }

    /** The clearance a footprint is kept by on a leg from (fx, fz) to (tx, tz): the foot radius, or less where the body or the
     *  target is closer (a body pressed close is never sealed in, a target beside it is reached); -1 when either is inside it. */
    public static double room(Box b, double fx, double fz, double tx, double tz, double clear) {
        double from = b.gap(fx, fz), to = b.gap(tx, tz);
        return from <= 0 || to <= 0 ? -1 : Math.max(0, Math.min(clear, Math.min(from, to)) - EPS);
    }

    /** No other footprint covers p by the clearance it is kept by on this leg. */
    private static boolean open(double[] p, List<Box> boxes, Box except, double fx, double fz, double tx, double tz, double clear) {
        for (var b : boxes) if (b != except && b.gap(p[0], p[1]) < room(b, fx, fz, tx, tz, clear)) return false;
        return true;
    }

    /**
     * Where a body of foot radius {@code clear} stands nearest (tx, tz) with its foot clear of every footprint: the point itself
     * when it already is, else the exits straight out across each footprint it overlaps, nearest first (empty when none is clear).
     */
    public static List<double[]> outside(double tx, double tz, double clear, List<Box> boxes) {
        var out = new ArrayList<double[]>();
        for (var b : boxes) {
            if (b.gap(tx, tz) >= clear) continue;
            double u = b.u(tx, tz), v = b.v(tx, tz);
            for (int s = -1; s <= 1; s += 2) {
                out.add(b.at(s * (b.a + clear + PAST), v));
                out.add(b.at(u, s * (b.b + clear + PAST)));
            }
        }
        if (out.isEmpty()) return List.of(new double[]{tx, tz});
        out.removeIf(p -> boxes.stream().anyMatch(b -> b.gap(p[0], p[1]) < clear));
        out.sort(Comparator.comparingDouble(p -> Math.hypot(p[0] - tx, p[1] - tz)));
        return out;
    }
}
