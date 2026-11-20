package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.Predicate;

/** A swept ground step against a partner's horizontally expanded body box. */
public final class SeparationStep {
    private SeparationStep() {}

    // A quarter model unit of daylight before releasing a pair. Re-entry requires actual body contact.
    public static final double RELEASE_GAP = 1.0 / 64;
    public static final double SHOVE = .035;
    public static final double MAX_YIELD = 1.8;

    public static boolean clear(double x, double z, double clearance, double dx, double dz) {
        return clear(x, dx, clearance) || clear(z, dz, clearance);
    }

    /** Start at the real body face. Once pressed, finish opening a little daylight even at rest. */
    public static Vec crowd(double x, double z, double width, boolean pressed, int order, double yield,
                            Vec wanted, Predicate<Vec> walkable) {
        if (clear(x, z, pressed ? width + RELEASE_GAP : width, wanted.x(), wanted.z())) return wanted;
        double gap = Math.max(Math.abs(x), Math.abs(z));
        double pace = Math.min(SHOVE * yield, width + RELEASE_GAP - gap);
        // Coincident feet choose opposite normals in the two calls.
        if (x == 0 && z == 0) x = Math.copySign(0.0, order);
        if (wanted.hlen() < pace) {
            wanted = Math.abs(x) >= Math.abs(z) ? new Vec(Math.copySign(pace, x), wanted.y(), 0)
                    : new Vec(0, wanted.y(), Math.copySign(pace, z));
        }
        return away(x, z, width + RELEASE_GAP, wanted, walkable, true);
    }

    public static Vec away(double x, double z, double clearance, Vec wanted, Predicate<Vec> walkable) {
        return away(x, z, clearance, wanted, walkable, false);
    }

    private static Vec away(double x, double z, double clearance, Vec wanted, Predicate<Vec> walkable, boolean crowd) {
        // One separating face for the entire step stays safe even if a wall clips either component.
        if (clear(x, z, clearance, wanted.x(), wanted.z())) return wanted;
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
        // Once a crowd step has cleared some of the flank, never undo it to follow inward steering.
        double side = acrossX ? z : x;
        if (crowd && side != 0) slide = Math.copySign(pace, side);
        var along = acrossX ? new Vec(0, wanted.y(), slide) : new Vec(slide, wanted.y(), 0);
        if (walkable.test(along)) return along;
        if (crowd && side != 0) return new Vec(0, wanted.y(), 0);
        var opposite = new Vec(-along.x(), wanted.y(), -along.z());
        return walkable.test(opposite) ? opposite : new Vec(0, wanted.y(), 0);
    }

    private static boolean clear(double offset, double step, double clearance) {
        return Math.abs(offset) >= clearance && Math.copySign(1, offset) * (offset + step) >= clearance;
    }
}
