package io.github.longshou.mcyukkuri.pet.social;

import io.github.longshou.mcyukkuri.coordination.DeadlineMap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** The only gate for visible badge reactions: a token bucket per nearby player (a separate small one for places no player watches) plus
 *  per-observer, per-pair and per-target limits. Transient by design; a restart gives at most one burst of {@code capacity} tokens.
 *  Pure bookkeeping (UUIDs and ticks), so it can be exercised with an injected clock. */
public final class BadgeBudget {
    /** Tick values; a tier-1 reaction costs 1 token, a multi-beat arc 2. */
    public record Settings(int refillTicks, int capacity, int observerCooldown, int pairCooldown, int targetCooldown, int targetPerDay,
                           int worldRefillTicks, int worldCapacity) {
        public static Settings defaults() { return new Settings(6000, 2, 12000, 24000, 6000, 4, 12000, 1); }
    }
    public enum Verdict { OK, NO_TOKENS, OBSERVER_COOLDOWN, PAIR_COOLDOWN, TARGET_COOLDOWN, TARGET_DAILY }
    private static final class Bucket { double tokens; long stamp; Bucket(double tokens, long stamp) { this.tokens = tokens; this.stamp = stamp; } }
    private record Pair(UUID observer, UUID subject) {}
    private static final class Target { long day = -1; int count; }

    private final LongSupplier clock;
    private Settings settings;
    private final Map<UUID, Bucket> players = new HashMap<>();
    private Bucket world;
    private final DeadlineMap<UUID> observers = new DeadlineMap<>(), micro = new DeadlineMap<>(), targetCooldowns = new DeadlineMap<>();
    private final DeadlineMap<Pair> pairs = new DeadlineMap<>();
    private final Map<UUID, Target> targets = new HashMap<>();

    public BadgeBudget(LongSupplier clock, Settings settings) { this.clock = clock; this.settings = settings; }
    public Settings settings() { return settings; }
    public void configure(Settings next) { settings = next; }
    public void reset() { players.clear(); world = null; observers.clear(); micro.clear(); pairs.clear(); targets.clear(); targetCooldowns.clear(); }

    private Bucket bucket(UUID viewer) {
        long now = clock.getAsLong();
        boolean far = viewer == null;
        int capacity = far ? settings.worldCapacity() : settings.capacity();
        int refill = Math.max(1, far ? settings.worldRefillTicks() : settings.refillTicks());
        Bucket b;
        if (far) { if (world == null) world = new Bucket(capacity, now); b = world; }
        else {
            if (players.size() > 256) players.values().removeIf(x -> x.tokens >= settings.capacity());
            b = players.computeIfAbsent(viewer, id -> new Bucket(capacity, now));
        }
        if (now > b.stamp) { b.tokens = Math.min(capacity, b.tokens + (now - b.stamp) / (double)refill); }
        b.stamp = now;
        return b;
    }
    /** Tokens currently available to the viewer (null viewer = the world bucket). */
    public double tokens(UUID viewer) { return bucket(viewer).tokens; }

    /** Non-mutating (apart from refilling): would this reaction be admitted right now? */
    public Verdict check(UUID viewer, int cost, UUID observer, UUID subject) {
        long now = clock.getAsLong();
        if (observers.active(observer, now)) return Verdict.OBSERVER_COOLDOWN;
        if (pairs.active(new Pair(observer, subject), now)) return Verdict.PAIR_COOLDOWN;
        var t = targets.get(subject);
        if (t != null) {
            if (targetCooldowns.active(subject, now)) return Verdict.TARGET_COOLDOWN;
            if (t.day == now / 24000 && t.count >= settings.targetPerDay()) return Verdict.TARGET_DAILY;
        }
        if (bucket(viewer).tokens + 1e-9 < cost) return Verdict.NO_TOKENS;
        return Verdict.OK;
    }
    /** Spend the tokens and start the cooldowns; call only after a reaction really started. */
    public void commit(UUID viewer, int cost, UUID observer, UUID subject) {
        long now = clock.getAsLong();
        var b = bucket(viewer); b.tokens = Math.max(0, b.tokens - cost);
        observers.put(observer, now + settings.observerCooldown());
        pairs.put(new Pair(observer, subject), now + (cost >= 2 ? settings.pairCooldown() : settings.pairCooldown() / 2));
        var t = targets.computeIfAbsent(subject, id -> new Target());
        targetCooldowns.put(subject, now + settings.targetCooldown());
        if (t.day != now / 24000) { t.day = now / 24000; t.count = 0; }
        t.count++;
        if (observers.size() > 512) observers.prune(now);
        if (pairs.size() > 1024) pairs.prune(now);
        if (targets.size() > 512) targets.entrySet().removeIf(e -> {
            if (targetCooldowns.active(e.getKey(), now) || e.getValue().day == now / 24000) return false;
            targetCooldowns.remove(e.getKey()); return true;
        });
    }
    /** Tier 0: a glance costs nothing but is limited to once a minute per observer. */
    public boolean microAllowed(UUID observer) {
        long now = clock.getAsLong();
        if (micro.active(observer, now)) return false;
        if (micro.size() > 512) micro.prune(now);
        micro.put(observer, now + 1200); return true;
    }
}
