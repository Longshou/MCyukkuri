package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * The owner of one choreography (spec 11.5): the component that conducts the ceremony embeds this, ticks it from its passive
 * clock and saves it under one key. {@link #roleOf} is how a role's goal finds its ceremony: it scans the live conductors
 * near the body, so nothing is stored on the role body. The registry is server-only, bounded and never saved.
 */
public final class Conductor {
    private static final Map<UUID, Conductor> LIVE = new LinkedHashMap<>();
    private static final int MAX = 256;

    private final YukkuriEntity owner;
    private final String key;
    private Choreography running;

    public Conductor(YukkuriEntity owner, String key) {
        this.owner = owner;
        this.key = key;
    }

    public Choreography choreography() { return running(); }

    public boolean active() { return running() != null; }

    public void start(Choreography next) {
        stop("replaced");
        running = next;
        next.attach(owner);
        register();
        next.begin();
    }

    /** Restores the ceremony saved under {@link #key}; the factory builds the domain's choreography from that tag. */
    public void load(CompoundTag parent, Function<CompoundTag, Choreography> factory) {
        running = null;
        LIVE.remove(owner.getUUID()); // a reloaded body is a new object: drop the stale entry of its UUID
        if (!parent.contains(key, Tag.TAG_COMPOUND)) return;
        var tag = parent.getCompound(key);
        var restored = factory.apply(tag);
        if (restored == null) return;
        restored.attach(owner);
        restored.load(tag);
        if (!restored.running()) return;
        running = restored;
        register();
    }

    public void save(CompoundTag parent) {
        if (running() == null) parent.remove(key);
        else parent.put(key, running.save());
    }

    /** The passive tick: advances the ceremony's own data and time beats only. */
    public void tick() {
        if (running == null || owner.level().isClientSide) return;
        if (!owner.isAlive() || owner.isRemoved()) stop("owner_gone");
        else if (running.running()) running.conduct();
        if (running != null && !running.running()) stop("ended");
    }

    public void stop(String why) {
        var ended = running;
        running = null;
        LIVE.remove(owner.getUUID(), this);
        if (ended != null) ended.cancel(why);
    }

    private Choreography running() { return running != null && running.running() ? running : null; }

    private void register() {
        LIVE.remove(owner.getUUID());
        LIVE.put(owner.getUUID(), this);
        if (LIVE.size() <= MAX) return;
        LIVE.values().removeIf(c -> c != this && (c.running() == null || c.owner.isRemoved()));
        var oldest = LIVE.values().iterator();
        while (LIVE.size() > MAX && oldest.hasNext()) if (oldest.next() != this) oldest.remove();
    }

    /** A body's place in a running ceremony: the choreography and its role there (engaged role first). */
    public record Role(Choreography choreography, String role) {
        public boolean engaged(YukkuriEntity body) { return role.equals(choreography.engaged(body.getUUID())); }
    }

    /** The nearest running ceremony within {@link Choreography#ROLE_RADIUS} that casts this body, or null; server side only. */
    public static Role roleOf(YukkuriEntity body) {
        if (body == null || body.level().isClientSide) return null;
        Role best = null;
        double nearest = Double.MAX_VALUE;
        for (var conductor : new ArrayList<>(LIVE.values())) {
            var c = conductor.running();
            var host = conductor.owner;
            if (c == null || !host.isAlive() || host.level() != body.level()) continue;
            double distance = host.distanceToSqr(body);
            if (distance > (double) Choreography.ROLE_RADIUS * Choreography.ROLE_RADIUS || distance >= nearest) continue;
            String role = c.roleOf(body.getUUID());
            if (role == null) continue;
            best = new Role(c, role);
            nearest = distance;
        }
        return best;
    }

    /** Forgets every conductor (server stop). */
    public static void reset() { LIVE.clear(); }
}
