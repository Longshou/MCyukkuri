package io.github.longshou.mcyukkuri.settlement;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

/** Food and bedding use the same order and admission rule before testing their own harvest stands. */
final class PlantSites {
    private static final int THREAT_RADIUS = 10;

    static boolean threatened(int strength, double dx, double dz) {
        return strength > 0 && dx * dx + dz * dz <= THREAT_RADIUS * THREAT_RADIUS;
    }

    static <T> Stream<T> nearest(List<T> candidates, ToDoubleFunction<T> distance, Predicate<T> available) {
        return candidates.stream().sorted(Comparator.comparingDouble(distance)).filter(available);
    }

    private PlantSites() {}
}
