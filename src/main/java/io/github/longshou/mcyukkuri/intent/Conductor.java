package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * The conductor of one choreography (spec 11.5): the component that owns the ceremony embeds this helper, ticks it from
 * its passive clock, saves and loads it under one NBT key, and resolves the bound role UUIDs through the server level.
 * It writes no body and calls no {@code BodyDriver.run}; the only thing it advances is the choreography's own beat table.
 *
 * <p>{@link #roleOf(YukkuriEntity)} is how a role's own goal discovers "am I in a running choreography": it scans the
 * live conductors within {@link Choreography#ROLE_RADIUS} blocks (cached for the current game tick) and asks each one's
 * {@link Choreography#roleOf(UUID)}. Nothing is stored on the role body, so there is no second copy of "who is in which
 * choreography" (design red line 9). The registry is bounded, server-thread only, and never saved.
 */
public final class Conductor {
    /** Live conductors, one entry per (owner, NBT key); bounded and server-thread only. */
    private static final Map<String, Conductor> LIVE = new LinkedHashMap<>();
    private static final int MAX_CONDUCTORS = 256;
    /** One game tick's answers, so a goal, its presentation and its status read the same lookup. */
    private static final Map<UUID, Optional<Role>> CACHE = new HashMap<>();
    private static final int MAX_CACHE = 256;
    private static long cacheTick = Long.MIN_VALUE;

    private final YukkuriEntity owner;
    private final String key;
    private Choreography choreography;

    /**
     * @param owner the body the conductor belongs to (the ceremony's director, e.g. the mother)
     * @param key   the NBT key this choreography is saved under inside the component's tag
     */
    public Conductor(YukkuriEntity owner, String key) {
        if (owner == null) throw new IllegalArgumentException("A conductor needs its owner body");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("A conductor needs its NBT key");
        this.owner = owner;
        this.key = key;
        register();
    }

    public String key() { return key; }

    /** The choreography being conducted, or null between ceremonies. */
    public Choreography choreography() { return choreography; }

    /** The current beat id, or null. */
    public String beat() { return choreography == null ? null : choreography.beat(); }

    public boolean running() { return choreography != null && choreography.running(); }

    /** Starts (or replaces) the ceremony and enters its first beat. */
    public void start(Choreography next) {
        invalidate();
        Choreography previous = choreography;
        if (previous != null) previous.cancel("replaced");
        choreography = next;
        if (next == null) return;
        next.attach(owner);
        register();
        next.begin();
    }

    /**
     * Restores a saved ceremony. {@code factory} builds the domain's choreography (it may read its own data from
     * {@code parent}); then the beat and the roles are loaded from the tag under {@link #key()}. Null when nothing was
     * saved or the factory returned nothing.
     */
    public Choreography load(CompoundTag parent, Supplier<Choreography> factory) {
        invalidate();
        Choreography previous = choreography;
        if (previous != null) previous.cancel("loaded");
        choreography = null;
        if (parent == null || factory == null || !parent.contains(key, Tag.TAG_COMPOUND)) return null;
        Choreography restored = factory.get();
        if (restored == null) return null;
        restored.attach(owner);
        restored.load(parent.getCompound(key));
        choreography = restored;
        register();
        return restored;
    }

    /** Writes the ceremony under {@link #key()}; removes the key when it ended, so a finished ceremony is not saved. */
    public void save(CompoundTag parent) {
        if (parent == null) return;
        Choreography running = choreography;
        if (running == null || !running.running()) {
            parent.remove(key);
            return;
        }
        parent.put(key, running.save());
    }

    /** The passive tick: advances the time beats. Never called from a Goal decision, never writes a body. */
    public void tick() {
        Choreography running = choreography;
        if (running == null || owner.level().isClientSide) return;
        if (!owner.isAlive() || owner.isRemoved()) {
            stop("owner_gone");
            return;
        }
        register();
        running.conduct();
    }

    /** Ends the ceremony (if any) and unregisters this conductor. */
    public void stop(String why) {
        invalidate();
        Choreography running = choreography;
        choreography = null;
        unregister();
        if (running != null) running.cancel(why == null ? "stopped" : why);
    }

    // ---- the role-side lookup ----------------------------------------------------------------------------------

    /**
     * Which running choreography (and which role in it) this body is playing, or null. Scans the live conductors within
     * {@link Choreography#ROLE_RADIUS} blocks of {@code body}, nearest conductor first, and caches the answer for the
     * current game tick. Client side always answers null: the lookup is a server decision.
     */
    public static Role roleOf(YukkuriEntity body) {
        if (body == null || body.level().isClientSide) return null;
        long now = body.level().getGameTime();
        if (cacheTick != now) {
            cacheTick = now;
            CACHE.clear();
        }
        Optional<Role> cached = CACHE.get(body.getUUID());
        if (cached != null) return cached.orElse(null);
        Role found = scan(body);
        if (CACHE.size() < MAX_CACHE) CACHE.put(body.getUUID(), Optional.ofNullable(found));
        return found;
    }

    /** Forgets the tick cache and every conductor; called on server stop so a new world starts clean. */
    public static void reset() {
        LIVE.clear();
        CACHE.clear();
        cacheTick = Long.MIN_VALUE;
    }

    /** A role binding: the running choreography and the role name this body plays in it. */
    public record Role(Choreography choreography, String role) {}

    private static Role scan(YukkuriEntity body) {
        Role best = null;
        double nearest = Double.MAX_VALUE;
        for (Conductor candidate : snapshot()) {
            Choreography running = candidate.choreography;
            if (running == null || !running.running()) continue;
            YukkuriEntity director = candidate.owner;
            if (director == null || director.isRemoved() || !director.isAlive()) continue;
            if (director.level() != body.level()) continue;
            double distance = director.distanceToSqr(body);
            if (distance > (double) Choreography.ROLE_RADIUS * Choreography.ROLE_RADIUS) continue;
            if (distance >= nearest) continue;
            String role = running.roleOf(body.getUUID());
            if (role == null) continue;
            best = new Role(running, role);
            nearest = distance;
        }
        return best;
    }

    private static List<Conductor> snapshot() {
        return new ArrayList<>(LIVE.values());
    }

    private void register() {
        String id = registryKey();
        LIVE.remove(id);
        LIVE.put(id, this);
        if (LIVE.size() <= MAX_CONDUCTORS) return;
        // Drop the finished or removed owners first; only then the oldest still-running ceremony.
        LIVE.values().removeIf(c -> c != this && c.finished());
        Iterator<Conductor> oldest = LIVE.values().iterator();
        while (LIVE.size() > MAX_CONDUCTORS && oldest.hasNext()) {
            if (oldest.next() != this) oldest.remove();
        }
    }

    private boolean finished() {
        return choreography == null || !choreography.running() || owner.isRemoved() || !owner.isAlive();
    }

    /** Role answers may be stale after any change of the table, so the tick cache is dropped. */
    static void invalidate() {
        CACHE.clear();
    }

    private void unregister() {
        LIVE.remove(registryKey(), this);
    }

    private String registryKey() {
        return owner.getUUID() + "#" + key;
    }
}
