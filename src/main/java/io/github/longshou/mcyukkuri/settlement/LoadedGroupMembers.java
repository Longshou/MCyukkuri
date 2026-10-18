package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import java.util.Objects;

/** Dimension-local candidates, not the persistent social ledger. Only the server thread changes this index. */
public final class LoadedGroupMembers {
    private static final Map<ServerLevel, LoadedGroupMembers> LEVELS = new IdentityHashMap<>();
    private record Entry(UUID id, UUID group, WeakReference<YukkuriEntity> entity) {}
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, Map<UUID, Entry>> groups = new HashMap<>();
    // Group buckets keep a query from polling every other community's not-yet-visible additions.
    private final Map<UUID, List<WeakReference<YukkuriEntity>>> pending = new HashMap<>();
    private boolean bootstrapped;

    private static LoadedGroupMembers of(ServerLevel level) { return LEVELS.computeIfAbsent(level, key -> new LoadedGroupMembers()); }

    /** Join fires before addition and can still be cancelled: do not read chunks or trust the proposed instance. */
    static void joining(ServerLevel level, YukkuriEntity entity) { of(level).waitFor(entity); }

    private void waitFor(YukkuriEntity entity) {
        UUID group = entity.settlement().groupId();
        if (group == null) return;
        var candidates = pending.computeIfAbsent(group, key -> new ArrayList<>());
        for (var candidate : candidates) if (candidate.get() == entity) return;
        candidates.add(new WeakReference<>(entity));
    }

    /** Covers runtime join/exile/load as well as changes before the initial entity addition. */
    static void changed(YukkuriEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        var index = of(level);
        if (level.getEntity(entity.getUUID()) == entity && !entity.isRemoved()) index.put(entity);
        else index.waitFor(entity);
    }

    static void leaving(ServerLevel level, YukkuriEntity entity) {
        var index = LEVELS.get(level);
        if (index == null) return;
        // Tracking-end alone is reversible without another Join event. Keep its weak identity while dormant.
        if (entity.isRemoved() || entity.level() != level) {
            var entry = index.entries.get(entity.getUUID());
            if (entry != null && entry.entity().get() == entity) index.remove(entry);
        }
    }

    private void put(YukkuriEntity entity) {
        UUID id = entity.getUUID(), group = entity.settlement().groupId();
        var previous = entries.get(id);
        if (previous != null && previous.entity().get() == entity && Objects.equals(previous.group(), group)) return;
        if (previous != null) remove(previous);
        if (group == null) return;
        var entry = new Entry(id, group, new WeakReference<>(entity));
        entries.put(id, entry);
        groups.computeIfAbsent(group, key -> new HashMap<>()).put(id, entry);
    }

    private void remove(Entry entry) {
        if (!entries.remove(entry.id(), entry)) return;
        var bucket = groups.get(entry.group());
        if (bucket != null) {
            bucket.remove(entry.id(), entry);
            if (bucket.isEmpty()) groups.remove(entry.group());
        }
    }

    private void confirm(ServerLevel level, UUID group) {
        var waiting = pending.get(group);
        if (waiting == null) return;
        var candidates = waiting.iterator();
        while (candidates.hasNext()) {
            var entity = candidates.next().get();
            if (entity == null || entity.isRemoved() || entity.level() != level || !group.equals(entity.settlement().groupId())) {
                candidates.remove();continue;
            }
            // Read the final UUID here, not a UUID temporarily present during Entity.load.
            var current = level.getEntity(entity.getUUID());
            if (current != null && current != entity) candidates.remove();
            else if (current == entity) { put(entity);candidates.remove(); }
            // A valid but not-yet-accessible section can begin tracking without another Join event.
            // Cancelled additions stay weak pending identities, never returned members or strong references.
        }
        if (waiting.isEmpty()) pending.remove(group);
    }

    /** A single bootstrap uses only already-visible entities; no UUID lookup ever requests an entity or chunk load. */
    private void bootstrap(ServerLevel level) {
        if (bootstrapped) return;
        bootstrapped = true;
        for (var entity : level.getAllEntities())
            if (entity instanceof YukkuriEntity y && !y.isRemoved() && y.level() == level && level.getEntity(y.getUUID()) == y) put(y);
    }

    public static List<YukkuriEntity> query(ServerLevel level, UUID group, AABB box, Predicate<YukkuriEntity> predicate) {
        if (group == null) return List.of();
        var index = of(level);
        index.confirm(level, group);
        index.bootstrap(level);
        var bucket = index.groups.get(group);
        if (bucket == null) return List.of();
        var out = new ArrayList<YukkuriEntity>();
        var stale = new ArrayList<Entry>();
        for (var entry : List.copyOf(bucket.values())) {
            var entity = entry.entity().get();
            if (entity == null || entity.isRemoved() || entity.level() != level || !entry.id().equals(entity.getUUID())
                    || !group.equals(entity.settlement().groupId())) {
                stale.add(entry);
                continue;
            }
            if (level.getEntity(entry.id()) == entity && entity.getBoundingBox().intersects(box) && predicate.test(entity)) out.add(entity);
        }
        for (var entry : stale) index.remove(entry);
        return List.copyOf(out);
    }

    /** Explicit compatibility fallback, at most once per caller: identity filtering never reruns its business predicate. */
    static List<YukkuriEntity> encounterOrder(ServerLevel level, AABB box, List<YukkuriEntity> candidates) {
        Set<YukkuriEntity> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(candidates);
        return List.copyOf(level.getEntitiesOfClass(YukkuriEntity.class, box, identities::contains));
    }

    static boolean hasTies(List<YukkuriEntity> candidates, ToDoubleFunction<YukkuriEntity> key) {
        var seen = new HashSet<Double>();
        for (var entity : candidates) if (!seen.add(key.applyAsDouble(entity))) return true;
        return false;
    }

    /** Only equal minima need the world's encounter order; no invented UUID tie-breaker. */
    static YukkuriEntity minimum(ServerLevel level, AABB box, List<YukkuriEntity> candidates, ToDoubleFunction<YukkuriEntity> key) {
        var tied = new ArrayList<YukkuriEntity>();
        double best = 0;
        for (var entity : candidates) {
            double value = key.applyAsDouble(entity);
            int comparison = tied.isEmpty() ? -1 : Double.compare(value, best);
            if (comparison < 0) { tied.clear(); best = value; }
            if (comparison <= 0) tied.add(entity);
        }
        if (tied.isEmpty()) return null;
        var ordered = tied.size() == 1 ? tied : encounterOrder(level, box, tied);
        return ordered.isEmpty() ? null : ordered.getFirst();
    }

    static void tick(MinecraftServer server) {
        for (var levelEntry : LEVELS.entrySet()) {
            var level = levelEntry.getKey();
            if (level.getServer() != server) continue;
            var index = levelEntry.getValue();
            for (UUID group : new ArrayList<>(index.pending.keySet())) index.confirm(level, group);
            // Also reap entities removed while already untracked: there may be no second Leave event.
            if (server.getTickCount() % 200 == 0) {
                var stale = new ArrayList<Entry>();
                for (var entry : index.entries.values()) {
                    var entity = entry.entity().get();
                    if (entity == null || entity.isRemoved() || entity.level() != level) stale.add(entry);
                }
                for (var entry : stale) index.remove(entry);
            }
        }
    }

    static void clear(ServerLevel level) { LEVELS.remove(level); }
    static void clear(MinecraftServer server) { LEVELS.keySet().removeIf(level -> level.getServer() == server); }
    private LoadedGroupMembers() {}
}
