package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.Predicate;

/** A swept ground step against a partner's horizontally expanded body box. */
public final class SeparationStep {
    public enum Turn {
        ORDINARY, PASS, WAIT, YIELD, AROUND;

        public double clearance(double width, boolean pressed) {
            return this == WAIT || this == YIELD || this == ORDINARY && pressed ? width + RELEASE_GAP : width;
        }
    }
    // Lateral coordinate of the last closed flank of this pair. Recheck it before reversing into that pocket.
    private double blockedFlank = Double.NaN;
    private boolean acrossX;

    // A quarter model unit of daylight before releasing a pair. Re-entry requires actual body contact.
    public static final double RELEASE_GAP = 1.0 / 64;
    public static final double SHOVE = .035;
    public static final double MAX_YIELD = 1.8;

    public static boolean clear(double x, double z, double clearance, double dx, double dz) {
        return clear(x, dx, clearance) || clear(z, dz, clearance);
    }

    /** Start at the real body face. Once pressed, finish opening a little daylight even at rest. */
    public Vec crowd(double x, double z, double width, boolean pressed, int order, double yield,
                            Vec wanted, Predicate<Vec> walkable) {
        return step(Turn.ORDINARY, x, z, width, pressed, order, yield, wanted, walkable);
    }

    public Vec step(Turn turn, double x, double z, double width, boolean pressed, int order, double yield,
                    Vec wanted, Predicate<Vec> walkable) {
        boolean faceX = Math.abs(x) >= Math.abs(z);
        if (!pressed || acrossX != faceX) blockedFlank = Double.NaN;
        acrossX = faceX;
        if (clear(x, z, turn.clearance(width, pressed), wanted.x(), wanted.z())) return wanted;
        double gap = Math.max(Math.abs(x), Math.abs(z));
        if (turn == Turn.PASS || turn == Turn.AROUND) {
            // Approach the occupied face within the yield margin, leaving room for roundoff in world coordinates.
            // The resident ahead can now step aside even when its own walking input is zero.
            double room = gap - width;
            if (room > RELEASE_GAP * .5) {
                double inward = Math.abs(acrossX ? wanted.x() : wanted.z());
                double scale = (room - RELEASE_GAP * .25) / inward;
                var toFace = new Vec(wanted.x()*scale, wanted.y(), wanted.z()*scale);
                if (walkable.test(toFace)) return toFace;
            }
            // Continue along a free face under the entrant's own power. A sleeper has no stride to redirect.
            return away(x, z, Math.min(width, gap), wanted, walkable, this);
        }
        double pace = Math.min(SHOVE * yield, width + RELEASE_GAP - gap);
        // Coincident feet choose opposite normals in the two calls.
        if (x == 0 && z == 0) x = Math.copySign(0.0, order);
        if (wanted.hlen() < pace) {
            wanted = Math.abs(x) >= Math.abs(z) ? new Vec(Math.copySign(pace, x), wanted.y(), 0)
                    : new Vec(0, wanted.y(), Math.copySign(pace, z));
        }
        return away(x, z, width + RELEASE_GAP, wanted, walkable, this);
    }

    public static Vec away(double x, double z, double clearance, Vec wanted, Predicate<Vec> walkable) {
        return away(x, z, clearance, wanted, walkable, null);
    }

    private static Vec away(double x, double z, double clearance, Vec wanted, Predicate<Vec> walkable, SeparationStep crowd) {
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
        double side = acrossX ? z : x;
        if (crowd != null) {
            if (!Double.isNaN(crowd.blockedFlank)) slide = Math.copySign(pace, side - crowd.blockedFlank);
            else if (side != 0) slide = Math.copySign(pace, side);
        }
        var along = lateral(acrossX, slide, wanted.y());
        var opposite = lateral(acrossX, -slide, wanted.y());
        if (walkable.test(along)) {
            if (crowd != null && Double.isNaN(crowd.blockedFlank) && !walkable.test(opposite))
                crowd.blockedFlank = side - slide;
            return along;
        }
        if (walkable.test(opposite) && (crowd == null || Double.isNaN(crowd.blockedFlank)
                || walkable.test(lateral(acrossX, crowd.blockedFlank - side, wanted.y())))) {
            if (crowd != null) crowd.blockedFlank = side + slide;
            return opposite;
        }
        return new Vec(0, wanted.y(), 0);
    }

    private static Vec lateral(boolean acrossX, double slide, double y) {
        return acrossX ? new Vec(0, y, slide) : new Vec(slide, y, 0);
    }

    private static boolean clear(double offset, double step, double clearance) {
        return Math.abs(offset) >= clearance && Math.copySign(1, offset) * (offset + step) >= clearance;
    }
}
