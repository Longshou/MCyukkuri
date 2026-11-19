package io.github.longshou.mcyukkuri.pose;

/** Lateral contact between two simultaneously posed triangle surfaces facing along the same axis. */
public final class SideSurface {
    // Arithmetic error when bringing a posed point back into its partner's frame (blocks).
    private static final double EPS = 1e-7;

    public record Contact(double separation, double height, double depth) {
        public boolean touches(double across) { return Math.abs(separation - across) <= EPS; }
    }

    public record Point(double x, double y, double z) {}

    private record Triangle(Point a, Point b, Point c, double minX, double maxX,
                            double low, double high, double front, double back) {
        Triangle(Point a, Point b, Point c) {
            this(a, b, c, Math.min(a.x, Math.min(b.x, c.x)), Math.max(a.x, Math.max(b.x, c.x)),
                    Math.min(a.y, Math.min(b.y, c.y)), Math.max(a.y, Math.max(b.y, c.y)),
                    Math.min(a.z, Math.min(b.z, c.z)), Math.max(a.z, Math.max(b.z, c.z)));
        }

        /** Surface X at this projected location, or NaN outside the triangle. */
        double at(Point p) {
            double ey = b.y - a.y, ez = b.z - a.z, fy = c.y - a.y, fz = c.z - a.z;
            double dy = p.y - a.y, dz = p.z - a.z;
            double d = cross(ey, ez, fy, fz);
            if (Math.abs(d) < EPS * EPS) return Double.NaN;
            double u = cross(dy, dz, fy, fz) / d, v = cross(ey, ez, dy, dz) / d;
            return u >= -EPS && v >= -EPS && u + v <= 1 + EPS ? a.x + u * (b.x - a.x) + v * (c.x - a.x) : Double.NaN;
        }
    }

    public static final class Pose {
        private final float[] vertices;
        private final double left, right;

        /** Consecutive triangle corners in model units, X positive toward the body's right. */
        public Pose(float[] vertices) {
            this.vertices = vertices;
            double left = 0, right = 0;
            for (int i = 0; i < vertices.length; i += 3) {
                left = Math.max(left, -vertices[i]);
                right = Math.max(right, vertices[i]);
            }
            this.left = left;
            this.right = right;
        }

        public double reach(boolean rightSide) { return rightSide ? right : left; }

        private Point corner(int i, double scale, double lift, double depth) {
            return new Point(vertices[i] * scale, vertices[i + 1] * scale + lift, vertices[i + 2] * scale + depth);
        }

        private Triangle[] triangles(double scale, double lift, double depth) {
            var triangles = new Triangle[vertices.length / 9];
            for (int i = 0; i < triangles.length; i++) {
                int j = 9 * i;
                triangles[i] = new Triangle(corner(j, scale, lift, depth), corner(j + 3, scale, lift, depth),
                        corner(j + 6, scale, lift, depth));
            }
            return triangles;
        }
    }

    /**
     * The center separation along X at which the right-hand skin of the second body first meets the left-hand skin of
     * the first. On the intersection of two projected triangles their lateral difference is affine. Its maximum lies
     * at a contained corner or an edge crossing, so a coarse cell or an envelope across time cannot create a contact.
     */
    public static Contact meet(Pose left, double leftScale, Pose right, double rightScale, double lift, double depth) {
        var a = left.triangles(leftScale, 0, 0);
        var b = right.triangles(rightScale, lift, depth);
        var best = new Search();
        for (var first : a) for (var second : b) {
            if (second.maxX - first.minX <= best.separation || first.high < second.low || second.high < first.low
                    || first.back < second.front || second.back < first.front) continue;
            best.compare(first, second);
        }
        return best.separation > 0 ? new Contact(best.separation, best.y, best.z) : null;
    }

    private static double cross(double uy, double uz, double vy, double vz) { return uy * vz - uz * vy; }

    private static final class Search {
        double separation, y, z;

        void compare(Triangle a, Triangle b) {
            corner(a.a, b, false); corner(a.b, b, false); corner(a.c, b, false);
            corner(b.a, a, true); corner(b.b, a, true); corner(b.c, a, true);
            edges(a.a, a.b, b); edges(a.b, a.c, b); edges(a.c, a.a, b);
        }

        private void corner(Point p, Triangle t, boolean right) {
            double x = t.at(p);
            consider(right ? p.x - x : x - p.x, p.y, p.z);
        }

        private void edges(Point p, Point q, Triangle b) {
            edge(p, q, b.a, b.b); edge(p, q, b.b, b.c); edge(p, q, b.c, b.a);
        }

        private void edge(Point p, Point q, Point r, Point s) {
            double ey = q.y - p.y, ez = q.z - p.z, fy = s.y - r.y, fz = s.z - r.z;
            double d = cross(ey, ez, fy, fz);
            if (Math.abs(d) < EPS * EPS) return;
            double u = cross(r.y - p.y, r.z - p.z, fy, fz) / d, v = cross(r.y - p.y, r.z - p.z, ey, ez) / d;
            if (u >= 0 && u <= 1 && v >= 0 && v <= 1)
                consider(r.x + v * (s.x - r.x) - p.x - u * (q.x - p.x), p.y + u * ey, p.z + u * ez);
        }

        private void consider(double distance, double height, double depth) {
            if (distance > separation) { separation = distance; y = height; z = depth; }
        }
    }

    private SideSurface() {}
}
