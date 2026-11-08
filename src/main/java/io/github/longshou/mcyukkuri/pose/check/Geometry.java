package io.github.longshou.mcyukkuri.pose.check;

import java.util.*;
import org.joml.Vector3d;

/** Triangle surfaces in model units. Open surfaces are never filled or convexified. */
public final class Geometry {
    private Geometry() {}
    // Numerical intersection tolerance, not the gameplay penetration allowance.
    private static final double EPS = 1e-8;
    public record Region(Vector3d center, double radius) {
        public boolean contains(Vector3d p) { return center.distanceSquared(p) <= radius * radius; }
    }
    public record Triangle(Vector3d a, Vector3d b, Vector3d c) {
        Vector3d normal() { return new Vector3d(b).sub(a).cross(new Vector3d(c).sub(a)); }
        double hit(Vector3d p, Vector3d d) {
            var e = new Vector3d(b).sub(a); var f = new Vector3d(c).sub(a);
            var h = new Vector3d(d).cross(f); double det = e.dot(h);
            if (Math.abs(det) < EPS) return Double.NaN;
            var s = new Vector3d(p).sub(a); double u = s.dot(h) / det;
            if (u < -EPS || u > 1 + EPS) return Double.NaN;
            var q = s.cross(e); double v = d.dot(q) / det;
            return v < -EPS || u + v > 1 + EPS ? Double.NaN : f.dot(q) / det;
        }
        double distance(Vector3d p) {
            var n = normal(); double n2 = n.lengthSquared();
            if (n2 > EPS * EPS) {
                double t = new Vector3d(a).sub(p).dot(n) / n2;
                // hit tests barycentrics, including a projection on the triangle itself.
                if (Double.isFinite(hit(p, n))) return Math.abs(t) * Math.sqrt(n2);
            }
            return Math.min(segment(p, a, b), Math.min(segment(p, b, c), segment(p, c, a)));
        }
    }
    private static double segment(Vector3d p, Vector3d a, Vector3d b) {
        var d = new Vector3d(b).sub(a); double n = d.lengthSquared();
        double t = n == 0 ? 0 : Math.clamp(new Vector3d(p).sub(a).dot(d) / n, 0, 1);
        return new Vector3d(a).fma(t, d).distance(p);
    }
    public static final class Mesh {
        public final List<Triangle> triangles;
        public final boolean closed;
        private final Node tree;
        public Mesh(List<Triangle> triangles, boolean closed) {
            if (triangles.isEmpty()) throw new IllegalArgumentException("empty triangle mesh");
            this.triangles = List.copyOf(triangles); this.closed = closed;
            tree = new Node(new ArrayList<>(triangles));
        }
        List<Triangle> candidates(Vector3d p, Vector3d q) {
            var out = new ArrayList<Triangle>(); tree.query(p, q, out); return out;
        }
        boolean inside(Vector3d p) {
            if (!closed) return false;
            var d = new Vector3d(1, .371390676354, .52911382);
            var q = new Vector3d(p).fma(tree.max.distance(tree.min) * 3 + p.distance(tree.min) + 1, d);
            var hits = new TreeSet<Double>();
            for (var tri : candidates(p, q)) {
                double t = tri.hit(p, d);
                if (t > EPS) hits.add(t);
            }
            int count = 0; double last = -1;
            for (double t : hits) if (t - last > EPS) { count++; last = t; }
            return count % 2 == 1;
        }
        double distance(Vector3d p) { return tree.distance(p, Double.POSITIVE_INFINITY); }
    }
    /** Small BVH keeps real fitted meshes usable without a quadratic all-triangle scan. */
    private static final class Node {
        final Vector3d min = new Vector3d(Double.POSITIVE_INFINITY), max = new Vector3d(Double.NEGATIVE_INFINITY);
        List<Triangle> leaf; Node left, right;
        Node(List<Triangle> ts) {
            for (var t : ts) for (var p : List.of(t.a, t.b, t.c)) { min.min(p); max.max(p); }
            if (ts.size() <= 12) { leaf = ts; return; }
            var size = new Vector3d(max).sub(min); int axis = size.maxComponent();
            ts.sort(Comparator.comparingDouble(t -> (t.a.get(axis) + t.b.get(axis) + t.c.get(axis)) / 3));
            int mid = ts.size() / 2;
            left = new Node(new ArrayList<>(ts.subList(0, mid))); right = new Node(new ArrayList<>(ts.subList(mid, ts.size())));
        }
        void query(Vector3d p, Vector3d q, List<Triangle> out) {
            for (int k = 0; k < 3; k++) if (Math.max(p.get(k), q.get(k)) < min.get(k) - EPS || Math.min(p.get(k), q.get(k)) > max.get(k) + EPS) return;
            if (leaf != null) out.addAll(leaf); else { left.query(p, q, out); right.query(p, q, out); }
        }
        double distance(Vector3d p, double best) {
            double d2 = 0;
            for (int k = 0; k < 3; k++) { double d = Math.max(0, Math.max(min.get(k) - p.get(k), p.get(k) - max.get(k))); d2 += d*d; }
            if (d2 >= best*best) return best;
            if (leaf != null) { for (var t : leaf) best = Math.min(best, t.distance(p)); return best; }
            return right.distance(p, left.distance(p, best));
        }
    }
    public record Penetration(double depth, Vector3d point) {}
    public static Penetration penetration(Mesh a, Mesh b, List<Region> allowed) {
        var x = directed(a, b, allowed); var y = directed(b, a, allowed);
        return x.depth >= y.depth ? x : y;
    }
    private static boolean allowed(Vector3d p, List<Region> regions) { return regions.stream().anyMatch(r -> r.contains(p)); }
    private static Penetration directed(Mesh a, Mesh b, List<Region> allowed) {
        double worst = 0; Vector3d where = new Vector3d();
        var seen = new HashSet<Vector3d>();
        for (var tri : a.triangles) {
            var vs = List.of(tri.a, tri.b, tri.c);
            for (int i = 0; i < 3; i++) {
                var p = vs.get(i); var q = vs.get((i + 1) % 3); var d = new Vector3d(q).sub(p);
                if (seen.add(p) && !allowed(p, allowed) && b.inside(p)) {
                    double depth = b.distance(p); if (depth > worst) { worst = depth; where = p; }
                }
                // Edge/surface crossings catch thin sheets and crossings with no interior vertices.
                for (var surface : b.candidates(p, q)) {
                    double t = surface.hit(p, d);
                    if (!(t > EPS && t < 1 - EPS)) continue;
                    var hit = new Vector3d(p).fma(t, d);
                    if (allowed(hit, allowed)) continue;
                    if (b.closed) {
                        // A segment along a solid's boundary may meet a side triangle, but never enters it.
                        // Probe on both sides of the hit; exact resting contact must have zero penetration.
                        double step = Math.min(Math.min(t, 1 - t) / 2, 1e-5 / d.length());
                        var before = new Vector3d(p).fma(t - step, d);
                        var after = new Vector3d(p).fma(t + step, d);
                        if (!(b.inside(before) && b.distance(before) > EPS)
                                && !(b.inside(after) && b.distance(after) > EPS)) continue;
                    }
                    var n = surface.normal(); double length = n.length();
                    double depth = Math.min(t, 1 - t) * Math.abs(d.dot(n)) / length;
                    if (depth > worst) { worst = depth; where = hit; }
                }
            }
        }
        return new Penetration(worst, where);
    }
    /** Signed clearance along support up, at the lowest rider skin points in that frame. */
    public static double gap(List<Vector3d> skin, Mesh support, Vector3d up) {
        double low = skin.stream().mapToDouble(p -> p.dot(up)).min().orElseThrow();
        double worst = 0; boolean found = false;
        for (var p : skin) if (p.dot(up) <= low + EPS) {
            double nearest = Double.NaN;
            for (var tri : support.triangles) {
                double t = tri.hit(p, up);
                if (Double.isFinite(t) && (!Double.isFinite(nearest) || Math.abs(t) < Math.abs(nearest))) nearest = t;
            }
            if (!Double.isFinite(nearest)) throw new IllegalArgumentException("no support triangle under lowest skin point");
            if (!found || Math.abs(nearest) > Math.abs(worst)) worst = -nearest;
            found = true;
        }
        // A rounded body can rest on a rim away from its globally lowest vertex.
        // Preserve penetration below the support; for positive clearance include
        // actual body-surface contacts instead of assuming support is a flat floor.
        if (worst > 0) for (var p : skin) worst = supportedGap(worst, support.distance(p));
        return worst;
    }
    /** Sufficient scalar observations for a compact contact-band replay (model units). */
    public static double supportedGap(double lowest, double contactDistance) {
        return lowest > 0 ? Math.min(lowest, contactDistance) : lowest;
    }
}
