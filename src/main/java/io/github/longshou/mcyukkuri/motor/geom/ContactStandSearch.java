package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/** Contact stands must land on the requested sole height, including on slabs and stairs. */
public final class ContactStandSearch {
    public static final double SOLE_EPSILON = 1e-4;

    public static boolean sameLevel(double feet, double level) {
        return Math.abs(feet - level) <= SOLE_EPSILON;
    }

    /** Below this squared displacement Entity.move leaves its position unchanged. */
    public static boolean insideMovementResolution(double distanceSquared) { return distanceSquared <= 1e-7; }

    /** The candidates are ordered nearest first. Reject an unsupported or unreachable candidate and try the next bearing. */
    public static <B, S, R> R firstReachable(List<B> bearings, double level, Function<B, S> support,
                                             ToDoubleFunction<S> height, Function<S, R> route) {
        for (B bearing : bearings) {
            S stand = support.apply(bearing);
            if (stand == null || !sameLevel(height.applyAsDouble(stand), level)) continue;
            R reachable = route.apply(stand);
            if (reachable != null) return reachable;
        }
        return null;
    }

    private ContactStandSearch() {}
}
