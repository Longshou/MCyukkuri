package io.github.longshou.mcyukkuri.client;

import java.util.LinkedHashMap;
import java.util.Map;

/** Access-ordered cache for render-thread state tied to the current assets or level. */
final class BoundedRenderCache<K,V> extends LinkedHashMap<K,V> {
    private final int capacity;

    BoundedRenderCache(int capacity) {
        super(capacity,.75f,true);
        this.capacity=capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
        return size()>capacity;
    }
}
