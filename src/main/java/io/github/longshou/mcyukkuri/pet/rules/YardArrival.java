package io.github.longshou.mcyukkuri.pet.rules;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** An escort can finish at any free, supported stand in the lead's reach inside the yard. */
public final class YardArrival {
    private YardArrival() {}

    public static boolean withinReach(double squaredDistance, double reach) {
        return squaredDistance <= reach * reach;
    }

    public static <Cell, Stand> Set<Cell> stands(Iterable<Cell> cells, Function<Cell, Stand> freeStand,
                                                 Predicate<Stand> inside) {
        var stands = new HashSet<Cell>();
        for (var cell : cells) {
            var stand = freeStand.apply(cell);
            if (stand != null && inside.test(stand)) stands.add(cell);
        }
        return stands;
    }
}
