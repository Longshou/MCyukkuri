package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonArray;
import io.github.longshou.mcyukkuri.pose.check.PoseGeometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Certifies zero braid/body penetration in the same rounded world coordinates as the triangle replay. */
public final class BraidClearance {
    private final int[] body, braid;
    private final float[] points;
    private final double[] planes;
    private final Vector3f point = new Vector3f();
    private final Vector3f[] triangle = {new Vector3f(), new Vector3f(), new Vector3f()};

    public static BraidClearance of(JsonArray parts) {
        int[] braid = vertices(parts, "braid");
        return braid.length == 0 ? null : new BraidClearance(vertices(parts, "body"), braid);
    }

    private BraidClearance(int[] body, int[] braid) {
        this.body = body;
        this.braid = braid;
        points = new float[body.length * 3];
        planes = new double[body.length / 3 * 4];
    }

    private static int[] vertices(JsonArray parts, String group) {
        int count = 0;
        for (var raw : parts) {
            var p = raw.getAsJsonObject();
            if (PoseGeometry.group(p.get("name").getAsString()).equals(group)) count += p.get("count").getAsInt();
        }
        int[] indices = new int[count];
        int at = 0;
        for (var raw : parts) {
            var p = raw.getAsJsonObject();
            if (!PoseGeometry.group(p.get("name").getAsString()).equals(group)) continue;
            int start = p.get("start").getAsInt(), end = start + p.get("count").getAsInt();
            for (int i = start; i < end; i++) indices[at++] = i;
        }
        return indices;
    }

    public boolean clear(WallYield.Skinner skin, Matrix4f basis) {
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < body.length; i++) {
            skin.skin(body[i], point);
            basis.transformPosition(point);
            points[3*i] = point.x; points[3*i+1] = point.y; points[3*i+2] = point.z;
            cx += point.x; cy += point.y; cz += point.z;
        }
        cx /= body.length; cy /= body.length; cz /= body.length;
        // Each face normal provides a candidate direction. The furthest body vertex supplies its
        // supporting plane, so the certificate also encloses a bent or nonconvex body.
        for (int i = 0, p = 0; i < points.length; i += 9, p += 4) {
            double ax = points[i+3]-points[i], ay = points[i+4]-points[i+1], az = points[i+5]-points[i+2];
            double bx = points[i+6]-points[i], by = points[i+7]-points[i+1], bz = points[i+8]-points[i+2];
            double x = ay*bz-az*by, y = az*bx-ax*bz, z = ax*by-ay*bx;
            if ((points[i]-cx)*x + (points[i+1]-cy)*y + (points[i+2]-cz)*z < 0) { x = -x; y = -y; z = -z; }
            double max = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < points.length; j += 3)
                max = Math.max(max, x*points[j] + y*points[j+1] + z*points[j+2]);
            planes[p] = x; planes[p+1] = y; planes[p+2] = z;
            planes[p+3] = max + 1e-5 * Math.sqrt(x*x + y*y + z*z);
        }
        for (int i = 0; i < braid.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                skin.skin(braid[i+k], triangle[k]);
                basis.transformPosition(triangle[k]);
            }
            if (!outside()) return false;
        }
        return true;
    }

    private boolean outside() {
        for (int p = 0; p < planes.length; p += 4) {
            int k = 0;
            while (k < 3) {
                var v = triangle[k];
                if (planes[p]*v.x + planes[p+1]*v.y + planes[p+2]*v.z <= planes[p+3]) break;
                k++;
            }
            if (k == 3) return true;
        }
        return false;
    }
}
