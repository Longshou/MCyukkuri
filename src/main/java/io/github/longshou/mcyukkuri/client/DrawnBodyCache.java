package io.github.longshou.mcyukkuri.client;

import java.util.LinkedHashMap;

/** Bounded render cache that keeps bodies drawn in the current or previous frame. */
final class DrawnBodyCache<K,V> {
    static final int MAX_BODIES = 64;

    private static final class Entry<V> {
        V value;
        long frame;

        Entry(V value, long frame) {
            this.value = value;
            this.frame = frame;
        }
    }

    private final LinkedHashMap<K,Entry<V>> entries;
    private final int capacity;

    DrawnBodyCache(int capacity) {
        this.capacity = capacity;
        entries = new LinkedHashMap<>(capacity, .75f, true);
    }

    V get(K body, long frame) {
        var entry = entries.get(body);
        if (entry == null) return null;
        entry.frame = frame;
        return entry.value;
    }

    void put(K body, V value, long frame) {
        var entry = entries.get(body);
        if (entry != null) {
            entry.value = value;
            entry.frame = frame;
            return;
        }
        if (size() == capacity) {
            // An over-cap body still draws. Admit it once a slot missed a whole frame.
            if (entries.firstEntry().getValue().frame >= frame - 1) return;
            entries.pollFirstEntry();
        }
        entries.put(body, new Entry<>(value, frame));
    }

    void remove(K body) {
        entries.remove(body);
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
