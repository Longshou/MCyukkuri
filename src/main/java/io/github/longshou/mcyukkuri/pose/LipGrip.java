package io.github.longshou.mcyukkuri.pose;

import org.joml.Matrix4f;
import org.joml.Intersectionf;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Rigid grip at the centre of the posed mouth's outer lip plane, in model units. */
public final class LipGrip {
    public static final float HELD_HEIGHT = .45f;

    private final Quaternionf rotation;
    private final Quaternionf inverse;
    private final Vector3f low = new Vector3f(Float.POSITIVE_INFINITY);
    private final Vector3f high = new Vector3f(Float.NEGATIVE_INFINITY);

    public LipGrip(Matrix4f mouthBone) {
        rotation = new Quaternionf().setFromUnnormalized(mouthBone);
        inverse = new Quaternionf(rotation).conjugate();
    }

    public void include(Vector3f point) {
        inverse.transform(point);
        low.min(point);
        high.max(point);
    }

    public Vector3f point() {
        var center = new Vector3f(low).add(high).mul(.5f);
        center.z = low.z;
        return rotation.transform(center);
    }

    public Quaternionf rotation() { return rotation; }

    /** Half a model unit accommodates the final driven step and the sheet's lip-side edge. */
    public static boolean reaches(double distance, double scale) { return distance <= scale / 32; }

    /** Nearest point on the visible triangles of a worn piece. */
    public static Vector3f closestPoint(Vector3f[] triangles, Vector3f from) {
        var nearest = new Vector3f();
        var candidate = new Vector3f();
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < triangles.length; i += 3) {
            Intersectionf.findClosestPointOnTriangle(triangles[i], triangles[i + 1], triangles[i + 2], from, candidate);
            float distance = from.distanceSquared(candidate);
            if (distance < best) { best = distance; nearest.set(candidate); }
        }
        return nearest;
    }
}
