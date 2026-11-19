package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.BiPredicate;

/** Short flights onto or off a shelf. Every leg must have room for the flying body. */
public final class ShelfApproach {
    private ShelfApproach() {}

    public enum Landing { REPICK, APPROACH, TOUCHDOWN }

    /** Both the selected stand and the actual feet at ground contact must still be free. */
    public static Landing landing(boolean standClear, boolean groundedAtStand, boolean feetClear) {
        if (!standClear || groundedAtStand && !feetClear) return Landing.REPICK;
        return groundedAtStand ? Landing.TOUCHDOWN : Landing.APPROACH;
    }

    /** Living bodies can overlap in vanilla. A landing needs its own feet cell and disjoint body boxes. */
    public static boolean occupied(boolean sameCell, Vec min, Vec max, Vec otherMin, Vec otherMax) {
        return sameCell || Disc.level(min.x(), max.x(), otherMin.x(), otherMax.x())
                && Disc.level(min.y(), max.y(), otherMin.y(), otherMax.y())
                && Disc.level(min.z(), max.z(), otherMin.z(), otherMax.z());
    }

    public static Vec next(Vec from, Vec goal, BiPredicate<Vec, Vec> clear) {
        var leg = across(from, goal, clear);
        if (leg != null) return leg;
        // A lip over the body's edge can block even a vertical climb. Move level into a clear column first.
        Vec best = null;
        double length = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) continue;
                var side = new Vec(Math.floor(from.x()) + .5 + dx, from.y(), Math.floor(from.z()) + .5 + dz);
                double via = side.sub(from).hlen() + goal.sub(side).hlen();
                if (via < length && clear.test(from, side) && across(side, goal, clear) != null) {
                    best = side;
                    length = via;
                }
            }
        }
        return best;
    }

    private static Vec across(Vec from, Vec goal, BiPredicate<Vec, Vec> clear) {
        if (clear.test(from, goal)) return goal;
        // Steer just above the support so braking on approach still clears its top edge.
        var turn = new Vec(from.x(), goal.y() + .08, from.z());
        return clear.test(from, turn) && clear.test(turn, goal) ? turn : null;
    }
}
