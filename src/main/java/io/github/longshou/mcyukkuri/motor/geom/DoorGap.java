package io.github.longshou.mcyukkuri.motor.geom;

import java.util.Comparator;
import java.util.List;

/** Lateral positions the centre of a walking body can occupy between a door leaf and its jamb. */
public record DoorGap(double low, double high) {
    // Vanilla collision shapes treat faces this close as touching.
    private static final double CONTACT_EPSILON = 1e-7;
    public record Span(double min, double max) {}

    /** Pick a clear lane within half a grid step of the node, accounting for both sides of the footprint. */
    public static DoorGap fit(double node, double radius, List<Span> solids) {
        solids.sort(Comparator.comparingDouble(Span::min));
        double low = node - .5, limit = node + .5;
        DoorGap best = null;
        for (var solid : solids) {
            best = nearer(best, low, Math.min(limit, solid.min - radius), node);
            low = Math.max(low, solid.max + radius);
        }
        return nearer(best, low, limit, node);
    }

    private static DoorGap nearer(DoorGap best, double low, double high, double node) {
        if (low > high) return best;
        var gap = new DoorGap(low, high);
        return best == null || Math.abs(gap.lane(node) - node) < Math.abs(best.lane(node) - node) ? gap : best;
    }

    public double lane(double node) {
        return node >= low && node <= high ? node : (low + high) * .5;
    }

    /** A through route clears the trailing edge. A route ending in the door stops at its lane. */
    public boolean reached(double lateral, double along, double node, double target, double direction) {
        if (direction > 0) return along >= target - CONTACT_EPSILON;
        if (direction < 0) return along <= target + CONTACT_EPSILON;
        return Math.abs(along - target) <= CONTACT_EPSILON && Math.abs(lateral - lane(node)) <= CONTACT_EPSILON;
    }

    /** Approach diagonally up to the jamb, then maintain the lane until the trailing edge clears it. */
    public double aimAlong(double lateral, double along, double entry, double exit, double target) {
        if (lateral < low - CONTACT_EPSILON || lateral > high + CONTACT_EPSILON) {
            if (along <= entry) return Math.min(entry, target);
            if (along >= exit) return Math.max(exit, target);
        }
        return target;
    }
}
