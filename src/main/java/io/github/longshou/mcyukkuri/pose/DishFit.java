package io.github.longshou.mcyukkuri.pose;

import org.joml.Vector3f;
import org.joml.Intersectionf;

/** Model units. The bowl and saucer share the same fourteen-unit octagonal plan. */
public final class DishFit {
    public static final double RIM = 7;
    // Centre of the dry ceramic square between the two clipped corners of each face.
    public static final double CORNER = 5.5;

    public record Stand(double x, double z, double lipX, double lipZ, boolean perched) {}

    /** Three bites or two sips per loop; each first reaches the dish half a beat in. */
    public static int firstContact(int loopTicks, boolean drink) { return loopTicks / (drink ? 4 : 6); }

    public static Stand stand(double halfWidth, double soleRadius, double mouthDepth) {
        // The jar ends at three units; the outer support ends at seven.
        if (soleRadius <= RIM - CORNER && halfWidth <= CORNER - 3) {
            double along = mouthDepth / Math.sqrt(2);
            // The well ends at six units on the flat side and five at its clipped corner.
            return new Stand(CORNER, CORNER, Math.min(6, CORNER + along), Math.min(5, CORNER - along), true);
        }
        // A milliblock clears the collision face on the last driven step.
        return new Stand(RIM + Math.max(halfWidth, mouthDepth) + .016, 0, RIM - 1, 0, false);
    }

    private final Vector3f target = new Vector3f(), probe = new Vector3f(), nearest = new Vector3f(), delta = new Vector3f();
    private float best, front, low, high, outer, chin, rearFloor, progress;

    public void begin(Vector3f target, Vector3f mouth, float rimWidth, float progress) {
        this.target.set(target);
        // On a perch the full fit keeps the sole on the small ceramic corner from the first frame.
        this.progress = target.y <= 0 ? 1 : progress;
        chin = outer = target.z + rimWidth;
        rearFloor = target.y;
        // A perched body dips its lower lip; a grounded body presents the lip at the rim's height.
        probe.set(0, target.y, mouth.z);
        best = Float.POSITIVE_INFINITY;
    }

    public void lipTriangle(Vector3f a, Vector3f b, Vector3f c) {
        Intersectionf.findClosestPointOnTriangle(a, b, c, probe, nearest);
        float distance = nearest.distanceSquared(probe);
        if (distance >= best) return;
        best = distance;
        delta.set(target).sub(nearest);
        front = Math.max(a.z, Math.max(b.z, c.z));
        low = Math.min(a.y, Math.min(b.y, c.y));
        high = Math.max(a.y, Math.max(b.y, c.y));
    }

    /** The contacted triangle moves as a piece. The surrounding cheek follows it down to the planted sole. */
    private void reach(Vector3f p) {
        float depth = Math.clamp(p.z / front, 0, 1);
        float height = p.y >= low ? 1 : Math.clamp(p.y / low, 0, 1);
        float cheek = Math.clamp(1 - Math.max(0, p.y - high) / -front, 0, 1);
        float weight = depth * height * cheek * progress;
        // The patch rests flat on the wetted inner edge. Its neighbouring skin folds over the ceramic.
        p.y += weight * (target.y - Math.clamp(p.y, low, high));
        p.x += weight * delta.x;
        p.z += weight * delta.z;
        p.y = Math.max(0, p.y);
    }

    /** Lift the complete facets that pass over the ceramic at the current pose, including during the approach. */
    public void rimTriangle(Vector3f a, Vector3f b, Vector3f c) {
        reach(a); reach(b); reach(c);
        if (Math.min(a.z, Math.min(b.z, c.z)) < outer && Math.min(a.y, Math.min(b.y,c.y)) < target.y)
            chin = Math.max(chin, Math.max(a.z, Math.max(b.z, c.z)));
    }

    /** The front is supported by the rim. Settle the remaining sole on its original floor. */
    public void sole(Vector3f p) {
        reach(p);
        if (p.z > chin) rearFloor = Math.min(rearFloor, p.y);
    }

    public void press(Vector3f p) {
        reach(p);
        if (p.z <= chin) p.y = Math.max(p.y, target.y);
        else if (p.y < target.y && rearFloor < target.y)
            p.y = Math.max(0, target.y * (p.y - rearFloor) / (target.y - rearFloor));
    }
}
