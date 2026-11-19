package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.BiPredicate;

/** Short flights onto or off a shelf. Every leg must have room for the flying body. */
public final class ShelfApproach {
    private ShelfApproach() {}

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
