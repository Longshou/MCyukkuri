package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.Predicate;

/** A swept ground step against a partner's horizontally expanded body box. */
public final class SeparationStep {
    private SeparationStep() {}

    public static Vec away(double x, double z, double clearance, Vec wanted, Predicate<Vec> walkable) {
        // One separating face for the entire step stays safe even if a wall clips either component.
        if (clear(x, wanted.x(), clearance) || clear(z, wanted.z(), clearance)) return wanted;
        boolean acrossX = Math.abs(x) >= Math.abs(z);
        double gap = acrossX ? x : z;
        double outward = Math.copySign(1, gap);
        double tangent = acrossX ? wanted.z() : wanted.x();
        double pace = wanted.hlen();
        if (Math.abs(gap) < clearance) {
            // The contact pose can start inside the walking envelope. Open a gap as it walks out.
            double normal = Math.max((acrossX ? wanted.x() : wanted.z()) * outward, pace * .5);
            double scale = Math.min(1, pace / Math.hypot(normal, tangent));
            var step = acrossX ? new Vec(outward * normal * scale, wanted.y(), tangent * scale)
                    : new Vec(tangent * scale, wanted.y(), outward * normal * scale);
            if (walkable.test(step)) return step;
        }
        // Slide along the separating face when its outward side is a wall or brink.
        double slide = tangent == 0 ? (acrossX ? outward : -outward) * pace : Math.copySign(pace, tangent);
        var along = acrossX ? new Vec(0, wanted.y(), slide) : new Vec(slide, wanted.y(), 0);
        if (walkable.test(along)) return along;
        var opposite = new Vec(-along.x(), wanted.y(), -along.z());
        return walkable.test(opposite) ? opposite : new Vec(0, wanted.y(), 0);
    }

    private static boolean clear(double offset, double step, double clearance) {
        return Math.abs(offset) >= clearance && Math.copySign(1, offset) * (offset + step) >= clearance;
    }
}
