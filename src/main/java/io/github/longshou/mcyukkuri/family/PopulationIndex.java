package io.github.longshou.mcyukkuri.family;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Buckets match the 32-block local population query. The ledger owns exact positions and life status. */
final class PopulationIndex<D, T> {
    private record Cell<D>(D dimension, int x, int z) {}
    private final Map<Cell<D>, Set<T>> cells = new HashMap<>();
    private final Map<T, Cell<D>> locations = new HashMap<>();

    void locate(T member, D dimension, int x, int z) {
        int cx = x >> 5, cz = z >> 5;
        var old = locations.get(member);
        if (old != null && old.dimension.equals(dimension) && old.x == cx && old.z == cz) return;
        remove(member);
        var cell = new Cell<>(dimension, cx, cz);
        locations.put(member, cell);
        cells.computeIfAbsent(cell, unused -> new HashSet<>()).add(member);
    }

    void remove(T member) {
        var cell = locations.remove(member);
        if (cell == null) return;
        var bucket = cells.get(cell);
        bucket.remove(member);
        if (bucket.isEmpty()) cells.remove(cell);
    }

    int count(D dimension, int x, int z, int radius, Predicate<T> include) {
        int count = 0;
        for (int cx = (x - radius) >> 5; cx <= (x + radius) >> 5; cx++) {
            for (int cz = (z - radius) >> 5; cz <= (z + radius) >> 5; cz++) {
                var bucket = cells.get(new Cell<>(dimension, cx, cz));
                if (bucket != null) for (T member : bucket) if (include.test(member)) count++;
            }
        }
        return count;
    }

    static boolean within(double dx, double dz, int radius) {
        return dx * dx + dz * dz <= radius * radius;
    }
}
