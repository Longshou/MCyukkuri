package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.Custody;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import io.github.longshou.mcyukkuri.freezer.FreezerStorage;
import io.github.longshou.mcyukkuri.status.StatusEvents;

/**
 * Cross-dimension custody and the sole authority for directed object relationships.
 * Containers carry a token, never a spawnable NBT copy.
 */
public final class YukkuriWorldData extends SavedData {
    private record Captive(UUID entity, CompoundTag data) {}
    private record Edge(UUID from, UUID to) {}
    /** One individual's directed memory of one real player, NPC, or yukkuri. */
    public record Relation(int closeness, int fear, long lastInteraction, int fearBand) {
        public Relation {
            closeness = Math.clamp(closeness, -100, 100);
            fear = Math.clamp(fear, 0, 100);
            fearBand = fear >= 60 || fearBand == 2 && fear >= 55 ? 2
                    : fear >= 30 || fearBand > 0 && fear >= 25 ? 1 : 0;
        }
        public static final Relation NONE = new Relation(0, 0, 0, 0);
        public int strength() { return Math.abs(closeness) + fear; }
    }
    /** Actual completed interactions. Callers must not emit one for animation-only or interrupted actions. */
    public enum RelationEvent {
        FEED(8, -5),
        GENTLE_TALK(3, -2),
        TREATMENT(4, -3),
        ACCESSORY_RETURN(6, -6),
        RESCUE(10, -8),
        ATTACK(-20, 25),
        ACCESSORY_TAKEN(-10, 12),
        TREASURE_TAKEN(-10, 14),
        CONFISCATED(-6, 8);

        private final int closeness, fear;
        RelationEvent(int closeness, int fear) { this.closeness = closeness; this.fear = fear; }
        public int closeness() { return closeness; }
        public int fear() { return fear; }
    }
    public static final int MAX_RELATIONS_PER_SOURCE = 64;
    /** A one-shot physical-loss notice; the owner's matching token decides whether it still applies. */
    public record AccessoryLoss(UUID token, UUID player, String destroyedBy) {}
    /** A consumed whole accessory retires this old capability before its former owner can recover a duplicate. */
    public record AccessoryTransfer(UUID token, UUID recipient) {}
    private final Map<UUID, Captive> captives = new HashMap<>();
    private final Map<UUID, AccessoryLoss> accessoryConfiscations = new HashMap<>();
    private final Map<UUID, AccessoryTransfer> accessoryTransfers = new HashMap<>();
    private final Map<Edge, Relation> relations = new HashMap<>();
    /** Active sessions cannot be selected for ordinary forgetting; nested uses are reference counted. */
    private final Map<UUID, Map<UUID, Integer>> activeRelationProtections = new HashMap<>();
    /** A present owner remains protected independently of any short-lived activity using the same target. */
    private final Map<UUID, UUID> ownerRelations = new HashMap<>();
    private final NavigableSet<Edge> cleanupEdges = new TreeSet<>(Comparator.comparing(Edge::from).thenComparing(Edge::to));
    private Edge cleanupCursor;
    private final Map<UUID,Integer> cleanupCustody = new HashMap<>();
    private final ArrayDeque<UUID> cleanupRoots = new ArrayDeque<>();
    private Consumer<UUID> retain = cleanupRoots::add;
    public void bindCleanup(Consumer<UUID> retention) { retain=retention; }
    public boolean cleanupRootStep() {
        UUID id=cleanupRoots.poll();if(id==null)return false;retain.accept(id);return true;
    }
    public boolean capturedForCleanup(UUID id) { return cleanupCustody.containsKey(id); }
    private void retain(UUID id) { if(id!=null)retain.accept(id); }
    private void rememberCaptive(Captive captive) {
        retain(captive.entity);
        var family=captive.data.getCompound("YukkuriFamily");
        for(String key:new String[]{"Mother","Father","Partner"})if(family.hasUUID(key))retain(family.getUUID(key));

    }
    private void putCaptive(UUID token,Captive captive) {
        Captive old=captives.put(token,captive);
        if(old!=null)forgetCustody(old.entity);
        cleanupCustody.merge(captive.entity,1,Integer::sum);rememberCaptive(captive);
    }
    private void forgetCustody(UUID id) { cleanupCustody.computeIfPresent(id,(key,count)->count==1?null:count-1); }
    /** One directed edge per call; no all-edges scan for each dead individual. */
    public void cleanupEdgeStep(FamilyData family) {
        Edge edge=cleanupCursor==null?(cleanupEdges.isEmpty()?null:cleanupEdges.first()):cleanupEdges.higher(cleanupCursor);
        cleanupCursor=edge;if(edge==null)return;
        if(!relations.containsKey(edge)) { cleanupEdges.remove(edge);return; }
        if(!family.deadForCleanup(edge.from)&&!family.deadForCleanup(edge.to))return;
        relations.remove(edge);cleanupEdges.remove(edge);
        if (edge.to.equals(ownerRelations.get(edge.from))) ownerRelations.remove(edge.from);
        var active = activeRelationProtections.get(edge.from);
        if (active != null) {
            active.remove(edge.to);
            if (active.isEmpty()) activeRelationProtections.remove(edge.from);
        }
        if(!relationsIndexDirty) {
            var bucket=relationsBySource.get(edge.from);
            if(bucket!=null) { bucket.remove(edge.to);if(bucket.isEmpty())relationsBySource.remove(edge.from); }
        }
        setDirty();
    }
    /** Derived source buckets; the flat map remains the authority and the saved wire format. */
    private final Map<UUID, Map<UUID, Relation>> relationsBySource = new HashMap<>();
    private boolean relationsIndexDirty = true;
    private void rebuildRelationsIndex() {
        relationsBySource.clear();
        // Preserve the flat map's encounter order, including after unrelated edges resize it.
        relations.forEach((edge, relation) -> relationsBySource.computeIfAbsent(edge.from, id -> new LinkedHashMap<>()).put(edge.to, relation));
        relationsIndexDirty = false;
    }
    private final Set<UUID> releasing = new HashSet<>();
    private ServerLevel custodyLevel;
    public final FreezerStorage freezer = new FreezerStorage(this);
    public static YukkuriWorldData get(ServerLevel level) {
        var result = level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(YukkuriWorldData::new, YukkuriWorldData::load), "mcyukkuri_living");
        result.custodyLevel = level.getServer().overworld();
        return result;
    }
    /** Compatibility name for closeness. It never subtracts fear. */
    public int affinity(UUID from, UUID to) { return relation(from, to).closeness(); }
    public int fear(UUID from, UUID to) { return relation(from, to).fear(); }
    public Relation relation(UUID from, UUID to) {
        if (from == null || to == null) return Relation.NONE;
        return relations.getOrDefault(new Edge(from, to), Relation.NONE);
    }
    /**
     * Marks a target as non-evictable while it is an owner or a live interaction target.
     * Protection is runtime state: callers release transient activity protection on completion.
     */
    public void protectRelation(UUID from, UUID to) {
        if (from == null || to == null || from.equals(to)) return;
        activeRelationProtections.computeIfAbsent(from, ignored -> new HashMap<>()).merge(to, 1, Integer::sum);
    }
    public void releaseRelation(UUID from, UUID to) {
        var protectedTargets = activeRelationProtections.get(from);
        if (protectedTargets == null || to == null) return;
        protectedTargets.computeIfPresent(to, (ignored, count) -> count <= 1 ? null : count - 1);
        if (protectedTargets.isEmpty()) activeRelationProtections.remove(from);
    }
    /** Idempotently updates the durable owner protection while allowing active protection to nest. */
    public void protectOwnerRelation(UUID from, UUID owner) {
        if (from == null) return;
        if (owner == null || from.equals(owner)) {
            ownerRelations.remove(from);
            return;
        }
        ownerRelations.put(from, owner);
    }
    public boolean relationProtected(UUID from, UUID to) {
        return to != null && (to.equals(ownerRelations.get(from))
                || activeRelationProtections.getOrDefault(from, Map.of()).containsKey(to));
    }
    /** Remove runtime-only protection for identities whose relation records were dropped in bulk. */
    private void clearRelationProtections(Set<UUID> ids) {
        ownerRelations.entrySet().removeIf(entry -> ids.contains(entry.getKey()) || ids.contains(entry.getValue()));
        activeRelationProtections.entrySet().removeIf(entry -> {
            if (ids.contains(entry.getKey())) return true;
            entry.getValue().keySet().removeIf(ids::contains);
            return entry.getValue().isEmpty();
        });
    }
    public void rollbackEncounter(Set<UUID> allocated) {
        if (allocated == null || allocated.isEmpty()) return;
        relations.keySet().removeIf(edge -> allocated.contains(edge.from) || allocated.contains(edge.to));
        cleanupEdges.removeIf(edge -> allocated.contains(edge.from) || allocated.contains(edge.to));
        clearRelationProtections(allocated);
        relationsIndexDirty = true;
        setDirty();
    }
    /** Existing closeness-only entry point. New behavior should use {@link #interact}. */
    public void relate(UUID from, UUID to, int change) { interact(from, to, change, 0); }
    public Relation interact(UUID from, UUID to, RelationEvent event) {
        return event == null ? relation(from, to) : interact(from, to, event.closeness(), event.fear());
    }
    /**
     * Applies one completed directed interaction. A source remembers at most 64 targets:
     * owner and active targets are protected; otherwise the least-recent, weakest memory is forgotten.
     * If every existing memory is protected, the new object is deliberately not remembered.
     */
    public Relation interact(UUID from, UUID to, int closenessChange, int fearChange) {
        if (from == null || to == null || from.equals(to) || closenessChange == 0 && fearChange == 0) return relation(from, to);
        Edge edge = new Edge(from, to);
        Relation before = relations.get(edge);
        if (before == null && !makeRelationRoom(from)) return Relation.NONE;
        long now = custodyLevel == null ? 0 : custodyLevel.getGameTime();
        Relation after = new Relation(
                Math.clamp((before == null ? 0 : before.closeness()) + closenessChange, -100, 100),
                Math.clamp((before == null ? 0 : before.fear()) + fearChange, 0, 100),
                now, before == null ? 0 : before.fearBand());
        if (before == null) relationsIndexDirty = true;
        relations.put(edge, after);
        cleanupEdges.add(edge);
        if (!relationsIndexDirty) relationsBySource.computeIfAbsent(from, ignored -> new LinkedHashMap<>()).put(to, after);
        if (before == null || after.closeness() != before.closeness())
            StatusEvents.relationChanged(from, to, before == null ? 0 : before.closeness(), after.closeness());
        setDirty();
        return after;
    }
    /** A completed bowel movement eases fear without manufacturing closeness or deleting non-relation records. */
    public void defecated(UUID from) {
        if (from == null) return;
        boolean changed = false;
        for (var entry : new ArrayList<>(relations.entrySet())) {
            if (!entry.getKey().from.equals(from) || entry.getValue().fear() == 0) continue;
            Relation before = entry.getValue();
            Relation after = new Relation(before.closeness(), Math.max(0, before.fear() - 2), before.lastInteraction(), before.fearBand());
            relations.put(entry.getKey(), after);
            if (!relationsIndexDirty) {
                var bucket = relationsBySource.get(from);
                if (bucket != null) bucket.put(entry.getKey().to, after);
            }
            changed = true;
        }
        if (changed) setDirty();
    }
    private boolean makeRelationRoom(UUID from) {
        long count = relations.keySet().stream().filter(edge -> edge.from.equals(from)).count();
        if (count < MAX_RELATIONS_PER_SOURCE) return true;
        Edge candidate = relations.entrySet().stream()
                .filter(entry -> entry.getKey().from.equals(from) && !relationProtected(from, entry.getKey().to))
                .min(Comparator.comparingInt((Map.Entry<Edge, Relation> entry) -> entry.getValue().strength() <= 30 ? 0 : 1)
                        .thenComparingLong(entry -> entry.getValue().lastInteraction())
                        .thenComparingInt(entry -> entry.getValue().strength())
                        .thenComparing(entry -> entry.getKey().to))
                .map(Map.Entry::getKey).orElse(null);
        if (candidate == null) return false;
        Relation removed = relations.remove(candidate);
        cleanupEdges.remove(candidate);
        if (!relationsIndexDirty) {
            var bucket = relationsBySource.get(from);
            if (bucket != null) { bucket.remove(candidate.to); if (bucket.isEmpty()) relationsBySource.remove(from); }
        }
        return removed != null;
    }
    /** Every directed closeness score held by {@code from}, for compatibility status views. */
    public Map<UUID, Integer> affinitiesFrom(UUID from) {
        if (relationsIndexDirty) rebuildRelationsIndex();
        Map<UUID, Integer> result = new LinkedHashMap<>();
        var outgoing = relationsBySource.get(from);
        if (outgoing != null) outgoing.forEach((to, score) -> { if (score.closeness() != 0) result.put(to, score.closeness()); });
        return result;
    }
    /** Every directed full relation held by {@code from}; values are immutable snapshots. */
    public Map<UUID, Relation> relationsFrom(UUID from) {
        if (relationsIndexDirty) rebuildRelationsIndex();
        var outgoing = relationsBySource.get(from);
        return outgoing == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(outgoing));
    }
    public boolean isCaptured(UUID entity) { return captives.values().stream().anyMatch(c -> c.entity.equals(entity)); }
    public void accessoryDestroyed(UUID owner, UUID token, String cause) {
        if (owner == null || token == null || cause == null || cause.isEmpty()) return;
        accessoryConfiscations.put(owner, new AccessoryLoss(token, null, cause)); setDirty();
    }
    public AccessoryLoss consumeAccessoryLoss(UUID owner, UUID token) {
        var event = accessoryConfiscations.get(owner);
        if (event == null || !event.token.equals(token)) return null;
        accessoryConfiscations.remove(owner); setDirty(); return event;
    }
    public void clearAccessoryConfiscation(UUID owner) {
        if (accessoryConfiscations.remove(owner) != null) setDirty();
    }
    /** Persist the hand-off for an unloaded source owner; the matching old token is retired on its first server tick. */
    public void accessoryTransferred(UUID owner,UUID token,UUID recipient) {
        if(owner==null||token==null||recipient==null||owner.equals(recipient))return;
        accessoryTransfers.put(owner,new AccessoryTransfer(token,recipient));setDirty();
    }
    public UUID consumeAccessoryTransfer(UUID owner,UUID token) {
        var transfer=accessoryTransfers.get(owner);
        if(transfer==null||!transfer.token.equals(token))return null;
        accessoryTransfers.remove(owner);setDirty();return transfer.recipient;
    }
    /**
     * Playground field clear only (command/PlaygroundTools): acquaintances and pending accessory losses of individuals the clear
     * removed. Custody records are kept: a carrier or freezer may still hold the token.
     */
    public void forgetIndividuals(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        boolean changed = relations.keySet().removeIf(edge -> ids.contains(edge.from) || ids.contains(edge.to));
        boolean clearedCleanup = cleanupEdges.removeIf(edge -> ids.contains(edge.from) || ids.contains(edge.to));
        clearRelationProtections(ids);
        if (changed || clearedCleanup) relationsIndexDirty = true;
        changed |= accessoryConfiscations.keySet().removeAll(ids);
        changed |= accessoryTransfers.keySet().removeAll(ids);
        if (changed || clearedCleanup) setDirty();
    }
    /** Playground field clear only: a custody token whose delivery record was dropped with its shop or factory. Freezer leases stay. */
    public void forgetCaptive(UUID token) {
        if (token == null || freezer.contains(token)) return;
        Captive held = captives.remove(token);
        if (held != null) { forgetCustody(held.entity); setDirty(); }
    }
    public boolean hasToken(UUID token) { return captives.containsKey(token); }
    /** Transfer the sole capability; an old carrier copy can no longer release this individual. */
    public UUID rotateToken(UUID token) {
        if (token == null || freezer.contains(token)) return null;
        Captive held = captives.remove(token);
        if (held == null) return null;
        UUID next = UUID.randomUUID(); captives.put(next, held); setDirty(); return next;
    }
    /** Normalize the authoritative record before trade inspection; the returned copy cannot alter or duplicate custody. */
    public CompoundTag captiveSnapshot(UUID token) {
        Captive held = captives.get(token);
        if (held == null) return new CompoundTag();
        if (Custody.normalizeSnapshot(custodyLevel, held.data)) setDirty();
        return held.data.copy();
    }
    /** Death updates the sole authoritative snapshots, including freezer-leased tokens, without entity/world effects. */
    public void normalizeCustody(ServerLevel level) {
        for (var held : captives.values()) if (Custody.normalizeSnapshot(level, held.data)) setDirty();
    }
    public UUID capture(YukkuriEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) return captureRefused(entity,"not_alive_or_removed");
        if (!Custody.cleanup(entity)) return captureRefused(entity,"custody_cleanup");
        if (entity.isPassenger()) return captureRefused(entity,"passenger");
        if (entity.isVehicle()) return captureRefused(entity,"vehicle");
        if (isCaptured(entity.getUUID())) return captureRefused(entity,"already_captured");
        if(!entity.operations().end())return captureRefused(entity,"operation_cannot_end");
        entity.groupInteraction().stop();entity.playLife().stop();entity.social().stop();entity.pet().care().released();
        CompoundTag snapshot = new CompoundTag();
        if (!entity.save(snapshot)) return captureRefused(entity,"snapshot_failed");
        UUID token = UUID.randomUUID();
        putCaptive(token, new Captive(entity.getUUID(), snapshot)); setDirty();
        entity.discard();
        GameplayLog.event(entity,"transport","action=capture result=committed token={} owner={} role={} captive_count={}",
                token,Custody.view(entity).owner(),Custody.view(entity).role(),captives.size());
        return token;
    }
    private UUID captureRefused(YukkuriEntity entity,String reason){
        GameplayLog.limited(entity,"transport","capture:"+reason,200,"action=capture result=refused reason={}",reason);
        return null;
    }
    private YukkuriEntity releaseRefused(UUID token,ServerLevel level,Vec3 position,String reason){
        GameplayLog.worldLimited(level,"transport","release:"+token+":"+reason,200,
                "action=release result=refused token={} destination={} reason={}",token,position,reason);
        return null;
    }
    public YukkuriEntity release(UUID token, ServerLevel level, Vec3 position, float yaw) {
        if (freezer.contains(token)) return releaseRefused(token,level,position,"freezer_lease");
        Captive held = captives.get(token);
        if (held == null) return releaseRefused(token,level,position,"token_missing");
        for (ServerLevel dimension : level.getServer().getAllLevels()) if (dimension.getEntity(held.entity) != null) return releaseRefused(token,level,position,"identity_already_loaded");
        if (Custody.normalizeSnapshot(level, held.data)) setDirty();
        Entity loaded = EntityType.loadEntityRecursive(held.data.copy(), level, entity -> entity);
        if (!(loaded instanceof YukkuriEntity entity) || !entity.isAlive()) return releaseRefused(token,level,position,"snapshot_not_live_yukkuri");
        entity.moveTo(position.x, position.y, position.z, yaw, 0);
        entity.setDeltaMovement(Vec3.ZERO); entity.fallDistance = 0; entity.setPersistenceRequired();
        if (!level.noCollision(entity)) return releaseRefused(token,level,position,"collision");
        if (level.containsAnyLiquid(entity.getBoundingBox())) return releaseRefused(token,level,position,"liquid");
        releasing.add(entity.getUUID());
        boolean added;
        try { added = level.addFreshEntity(entity); }
        finally { releasing.remove(entity.getUUID()); }
        if (!added) return releaseRefused(token,level,position,"entity_add_rejected");
        captives.remove(token);forgetCustody(held.entity);setDirty();
        Custody.cleanup(entity);
        GameplayLog.event(entity,"transport","action=release result=committed token={} owner={} role={} captive_count={}",
                token,Custody.view(entity).owner(),Custody.view(entity).role(),captives.size());
        return entity;
    }
    public static void joining(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof YukkuriEntity entity && event.getLevel() instanceof ServerLevel level) {
            YukkuriWorldData data = get(level);
            // Also reject a stale world entity when a saved custody record owns its UUID.
            if (data.isCaptured(entity.getUUID()) && !data.releasing.contains(entity.getUUID())) event.setCanceled(true);
        }
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 3);
        ListTag containers = new ListTag();
        captives.forEach((token, held) -> {
            CompoundTag value = new CompoundTag(); value.putUUID("Token", token); value.putUUID("Entity", held.entity);
            value.put("Data", held.data.copy()); containers.add(value);
        });
        tag.put("Captives", containers);
        ListTag confiscations = new ListTag();
        accessoryConfiscations.forEach((owner, event) -> {
            CompoundTag value = new CompoundTag(); value.putUUID("Owner", owner); value.putUUID("Token", event.token);
            if (event.player != null) value.putUUID("Player", event.player);
            if (!event.destroyedBy.isEmpty()) value.putString("DestroyedBy", event.destroyedBy);
            confiscations.add(value);
        });
        tag.put("AccessoryConfiscations", confiscations);
        ListTag transfers = new ListTag();
        accessoryTransfers.forEach((owner, transfer) -> {
            CompoundTag value=new CompoundTag();value.putUUID("Owner",owner);value.putUUID("Token",transfer.token);value.putUUID("Recipient",transfer.recipient);transfers.add(value);
        });
        tag.put("AccessoryTransfers",transfers);
        tag.put("FreezerVaults", freezer.save());
        ListTag edges = new ListTag();
        relations.forEach((edge, relation) -> {
            CompoundTag value = new CompoundTag(); value.putUUID("From", edge.from); value.putUUID("To", edge.to);
            value.putInt("Closeness", relation.closeness()); value.putInt("Fear", relation.fear());
            value.putLong("LastInteraction", relation.lastInteraction()); value.putInt("FearBand", relation.fearBand()); edges.add(value);
        });
        tag.put("Relations", edges); return tag;
    }
    public static YukkuriWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        YukkuriWorldData result = new YukkuriWorldData();
        for (Tag item : tag.getList("Captives", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)item;
            if (value.hasUUID("Token") && value.hasUUID("Entity") && value.contains("Data", Tag.TAG_COMPOUND))
                result.putCaptive(value.getUUID("Token"), new Captive(value.getUUID("Entity"), value.getCompound("Data").copy()));
        }
        for (Tag item : tag.getList("AccessoryConfiscations", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)item;
            if (value.hasUUID("Owner") && value.hasUUID("Token") && (value.hasUUID("Player") || !value.getString("DestroyedBy").isEmpty()))
                result.accessoryConfiscations.put(value.getUUID("Owner"), new AccessoryLoss(value.getUUID("Token"),
                        value.hasUUID("Player") ? value.getUUID("Player") : null, value.getString("DestroyedBy")));
        }
        for (Tag item : tag.getList("AccessoryTransfers",Tag.TAG_COMPOUND)) {
            CompoundTag value=(CompoundTag)item;
            if(value.hasUUID("Owner")&&value.hasUUID("Token")&&value.hasUUID("Recipient"))
                result.accessoryTransfers.put(value.getUUID("Owner"),new AccessoryTransfer(value.getUUID("Token"),value.getUUID("Recipient")));
        }
        result.freezer.load(tag.getList("FreezerVaults", Tag.TAG_COMPOUND));
        for (Tag item : tag.getList("Relations", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)item;
            if (value.hasUUID("From") && value.hasUUID("To")) {
                Edge edge=new Edge(value.getUUID("From"),value.getUUID("To"));
                result.relations.put(edge, new Relation(
                        Math.clamp(value.getInt("Closeness"), -100, 100),
                        Math.clamp(value.getInt("Fear"), 0, 100),
                        Math.max(0, value.getLong("LastInteraction")), Math.clamp(value.getInt("FearBand"), 0, 2)));
                result.cleanupEdges.add(edge);
            }
        }
        return result;
    }
}
