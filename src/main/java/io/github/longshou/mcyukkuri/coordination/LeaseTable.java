package io.github.longshou.mcyukkuri.coordination;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Caller-owned, single-threaded leases. Tokens identify a session, never just an actor or kind. */
public final class LeaseTable<K, O> {
    /** Sessions with business-owned termination may explicitly use a non-expiring lease. */
    public static final long INDEFINITE = Long.MAX_VALUE;
    private final Map<K, Map<O, Long>> leases = new LinkedHashMap<>();
    private final long maximumRemaining;

    public LeaseTable() { this(Long.MAX_VALUE); }
    /** A bounded table invalidates leases whose remaining time grows beyond this limit after clock rollback. */
    public LeaseTable(long maximumRemaining) {
        if (maximumRemaining <= 0) throw new IllegalArgumentException("Non-positive lease duration");
        this.maximumRemaining = maximumRemaining;
    }

    private boolean live(long until, long now) {
        return maximumRemaining == Long.MAX_VALUE && until == INDEFINITE
                || until > now && DeadlineMap.elapsed(until, now) <= maximumRemaining;
    }

    public Set<O> owners(K key, long now) {
        var result = new LinkedHashSet<O>();
        var values = leases.get(key);
        if (values != null) values.forEach((owner, until) -> { if (live(until, now)) result.add(owner); });
        return Collections.unmodifiableSet(result);
    }

    public boolean owns(K key, O owner, long now) { return owners(key, now).contains(owner); }
    public boolean free(Collection<K> keys, long now) { return keys.stream().allMatch(key -> owners(key, now).isEmpty()); }

    /** Checks the whole roster before changing any key. Existing same-token leases may be renewed. */
    public boolean acquireAll(Collection<K> keys, O owner, long until, long now) {
        Objects.requireNonNull(owner);
        var unique = new LinkedHashSet<>(keys);
        if (unique.isEmpty() || unique.contains(null) || !live(until, now)) return false;
        if (unique.stream().anyMatch(key -> owners(key, now).stream().anyMatch(other -> !owner.equals(other)))) return false;
        for (K key : unique) {
            var values = leases.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            values.values().removeIf(deadline -> !live(deadline, now));
            values.put(owner, until);
        }
        return true;
    }

    /** Rebuild only: retain every authoritative old owner, including conflicting legacy sessions. */
    public void restore(K key, O owner, long until) {
        leases.computeIfAbsent(Objects.requireNonNull(key), ignored -> new LinkedHashMap<>())
                .put(Objects.requireNonNull(owner), until);
    }

    public boolean renew(K key, O owner, long until, long now) {
        if (!owns(key, owner, now) || !live(until, now)) return false;
        leases.get(key).put(owner, until); return true;
    }

    public boolean transfer(K key, O from, O to, long until, long now) {
        Objects.requireNonNull(to);
        if (!owns(key, from, now) || !live(until, now) || owners(key, now).size() != 1) return false;
        var values = leases.get(key); values.remove(from); values.put(to, until); return true;
    }

    public void release(K key, O owner) {
        var values = leases.get(key);
        if (values == null) return;
        values.remove(owner);
        if (values.isEmpty()) leases.remove(key);
    }

    public void releaseAll(Collection<K> keys, O owner) { for (K key : keys) release(key, owner); }
    /** Stored key count, including keys awaiting explicit pruning. */
    public int size() { return leases.size(); }
    public void prune(long now) {
        leases.values().forEach(values -> values.values().removeIf(until -> !live(until, now)));
        leases.values().removeIf(Map::isEmpty);
    }
    public void clear() { leases.clear(); }
}
