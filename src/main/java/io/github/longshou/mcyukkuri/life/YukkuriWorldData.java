package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

/** Cross-dimension custody and directed acquaintances. Containers carry a token, never a spawnable NBT copy. */
public final class YukkuriWorldData extends SavedData {
    private record Captive(UUID entity, CompoundTag data) {}
    private record Edge(UUID from, UUID to) {}
    private final Map<UUID, Captive> captives = new HashMap<>();
    private final Map<Edge, Integer> relations = new HashMap<>();
    /** Derived source buckets; the flat map remains the authority and the saved wire format. */
    private final Map<UUID, Map<UUID, Integer>> relationsBySource = new HashMap<>();
    private boolean relationsIndexDirty = true;
    private void rebuildRelationsIndex() {
        relationsBySource.clear();
        // Preserve the flat map's encounter order, including after unrelated edges resize it.
        relations.forEach((edge, score) -> relationsBySource.computeIfAbsent(edge.from, id -> new LinkedHashMap<>()).put(edge.to, score));
        relationsIndexDirty = false;
    }
    private final Set<UUID> releasing = new HashSet<>();
    public final io.github.longshou.mcyukkuri.freezer.FreezerStorage freezer = new io.github.longshou.mcyukkuri.freezer.FreezerStorage(this);
    public static YukkuriWorldData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(YukkuriWorldData::new, YukkuriWorldData::load), "mcyukkuri_living");
    }
    public int affinity(UUID from, UUID to) { return relations.getOrDefault(new Edge(from, to), 0); }
    public void rollbackEncounter(java.util.Set<UUID> allocated) {
        relations.keySet().removeIf(edge->allocated.contains(edge.from)||allocated.contains(edge.to));relationsIndexDirty=true;setDirty();
    }
    public void relate(UUID from, UUID to, int change) {
        if (from.equals(to)) return;
        Edge edge = new Edge(from, to);
        int before = relations.getOrDefault(edge, 0), after = Math.clamp(before + change, -100, 100);
        if (!relations.containsKey(edge)) relationsIndexDirty = true;
        relations.put(edge, after); setDirty();
        if (!relationsIndexDirty) relationsBySource.get(from).put(to, after);
        if (after != before) io.github.longshou.mcyukkuri.status.StatusEvents.relationChanged(from, to, before, after);
    }
    /** Every directed acquaintance score held by {@code from}, for the status registry. */
    public Map<UUID, Integer> affinitiesFrom(UUID from) {
        if (relationsIndexDirty) rebuildRelationsIndex();
        Map<UUID, Integer> result = new LinkedHashMap<>();
        var outgoing = relationsBySource.get(from);
        if (outgoing != null) outgoing.forEach((to, score) -> { if (score != 0) result.put(to, score); });
        return result;
    }
    public boolean isCaptured(UUID entity) { return captives.values().stream().anyMatch(c -> c.entity.equals(entity)); }
    public boolean hasToken(UUID token) { return captives.containsKey(token); }
    /** Transfer the sole capability; an old carrier copy can no longer release this individual. */
    public UUID rotateToken(UUID token) {
        if (token == null || freezer.contains(token)) return null;
        Captive held = captives.remove(token);
        if (held == null) return null;
        UUID next = UUID.randomUUID(); captives.put(next, held); setDirty(); return next;
    }
    /** Read-only trade inspection. Mutating the returned NBT cannot alter or duplicate custody. */
    public CompoundTag captiveSnapshot(UUID token) {
        Captive held = captives.get(token); return held == null ? new CompoundTag() : held.data.copy();
    }
    public UUID capture(YukkuriEntity entity) {
        if (!entity.isAlive() || entity.isRemoved() || entity.isPassenger() || entity.isVehicle() || isCaptured(entity.getUUID())) return null;
        if(!entity.operations().end())return null;
        entity.groupInteraction().stop();entity.playLife().stop();entity.social().stop();entity.pet().care().released();
        CompoundTag snapshot = new CompoundTag();
        if (!entity.save(snapshot)) return null;
        UUID token = UUID.randomUUID();
        captives.put(token, new Captive(entity.getUUID(), snapshot)); setDirty();
        entity.discard();
        return token;
    }
    public YukkuriEntity release(UUID token, ServerLevel level, Vec3 position, float yaw) {
        if (freezer.contains(token)) return null;
        Captive held = captives.get(token);
        if (held == null) return null;
        for (ServerLevel dimension : level.getServer().getAllLevels()) if (dimension.getEntity(held.entity) != null) return null;
        Entity loaded = EntityType.loadEntityRecursive(held.data.copy(), level, entity -> entity);
        if (!(loaded instanceof YukkuriEntity entity) || !entity.isAlive()) return null;
        entity.moveTo(position.x, position.y, position.z, yaw, 0);
        entity.setDeltaMovement(Vec3.ZERO); entity.fallDistance = 0; entity.setPersistenceRequired();
        if (!level.noCollision(entity) || level.containsAnyLiquid(entity.getBoundingBox())) return null;
        releasing.add(entity.getUUID());
        boolean added;
        try { added = level.addFreshEntity(entity); }
        finally { releasing.remove(entity.getUUID()); }
        if (!added) return null;
        captives.remove(token); setDirty();
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
        tag.putInt("Version", 1);
        ListTag containers = new ListTag();
        captives.forEach((token, held) -> {
            CompoundTag value = new CompoundTag(); value.putUUID("Token", token); value.putUUID("Entity", held.entity);
            value.put("Data", held.data.copy()); containers.add(value);
        });
        tag.put("Captives", containers);
        tag.put("FreezerVaults", freezer.save());
        ListTag edges = new ListTag();
        relations.forEach((edge, score) -> {
            CompoundTag value = new CompoundTag(); value.putUUID("From", edge.from); value.putUUID("To", edge.to);
            value.putInt("Affinity", score); edges.add(value);
        });
        tag.put("Relations", edges); return tag;
    }
    public static YukkuriWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        YukkuriWorldData result = new YukkuriWorldData();
        for (Tag item : tag.getList("Captives", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)item;
            if (value.hasUUID("Token") && value.hasUUID("Entity") && value.contains("Data", Tag.TAG_COMPOUND))
                result.captives.put(value.getUUID("Token"), new Captive(value.getUUID("Entity"), value.getCompound("Data").copy()));
        }
        result.freezer.load(tag.getList("FreezerVaults", Tag.TAG_COMPOUND));
        for (Tag item : tag.getList("Relations", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)item;
            if (value.hasUUID("From") && value.hasUUID("To")) result.relations.put(new Edge(value.getUUID("From"), value.getUUID("To")),
                    Math.clamp(value.getInt("Affinity"), -100, 100));
        }
        return result;
    }
}
