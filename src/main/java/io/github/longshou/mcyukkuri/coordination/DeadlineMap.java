package io.github.longshou.mcyukkuri.coordination;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deadlines in a caller-supplied clock domain; reading never performs business cleanup. */
public final class DeadlineMap<K> {
    private final Map<K, Long> deadlines = new LinkedHashMap<>();

    public boolean active(K key, long now) {
        Long until = deadlines.get(key);
        return until != null && until > now;
    }

    /** Optional backwards-clock guard for callers whose original policy bounds remaining time. */
    public boolean active(K key, long now, long maximumRemaining) {
        Long until = deadlines.get(key);
        return until != null && until > now && until <= after(now, maximumRemaining);
    }

    public void put(K key, long until) { deadlines.put(key, until); }
    public void extend(K key, long until) { deadlines.merge(key, until, Math::max); }
    public int size() { return deadlines.size(); }
    public void remove(K key) { deadlines.remove(key); }
    public void prune(long now) { deadlines.values().removeIf(until -> until <= now); }
    /** Preserve callers whose cleanup keeps a deadline equal to now until the next tick. */
    public void pruneBefore(long now) { deadlines.values().removeIf(until -> until < now); }
    public Map<K, Long> snapshot() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(deadlines)); }
    public void clear() { deadlines.clear(); }

    public static long after(long now, long duration) {
        if (duration < 0) throw new IllegalArgumentException("Negative duration");
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    public static long elapsed(long now, long since) {
        if (now <= since) return 0;
        long elapsed = now - since;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }
}
