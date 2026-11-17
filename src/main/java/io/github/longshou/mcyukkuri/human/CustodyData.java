package io.github.longshou.mcyukkuri.human;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

/** Cross-dimension positive identity evidence and confirmed NPC deaths. Absence never proves death. */
public final class CustodyData extends SavedData {
    private final Map<UUID, Custody.OwnerKind> knownOwnerKinds = new HashMap<>();
    private final Map<UUID, Long> deadNpcOwners = new HashMap<>();
    private final Map<UUID, PlantBoundary> plants = new HashMap<>();

    record PlantBoundary(ResourceLocation dimension, BlockPos min, BlockPos max) {
        boolean contains(ServerLevel level, BlockPos feet) {
            return dimension.equals(level.dimension().location())
                    && feet.getX() >= min.getX() && feet.getX() <= max.getX()
                    && feet.getY() >= min.getY() && feet.getY() <= max.getY()
                    && feet.getZ() >= min.getZ() && feet.getZ() <= max.getZ();
        }
    }

    PlantBoundary plant(UUID site) { return plants.get(site); }

    void registerPlant(ServerLevel level, UUID site, BoundingBox bounds) {
        var boundary = new PlantBoundary(level.dimension().location(),
                new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()), new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
        if (!boundary.equals(plants.put(site, boundary))) setDirty();
    }

    public static CustodyData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(CustodyData::new, CustodyData::load), "mcyukkuri_custody");
    }

    public Custody.OwnerKind kind(UUID owner) { return knownOwnerKinds.getOrDefault(owner, Custody.OwnerKind.UNKNOWN); }
    public boolean deadNpc(UUID owner) { return owner != null && deadNpcOwners.containsKey(owner); }
    public void forget(Set<UUID> ids) {
        boolean changed = knownOwnerKinds.keySet().removeAll(ids);
        changed |= deadNpcOwners.keySet().removeAll(ids);
        changed |= plants.keySet().removeAll(ids);
        if (changed) setDirty();
    }

    /** Only actual entities, explicit typed assignment, or an existing persisted type supply evidence. */
    public void remember(UUID owner, Custody.OwnerKind kind) {
        if (owner == null || kind != Custody.OwnerKind.PLAYER && kind != Custody.OwnerKind.NPC) return;
        if (deadNpc(owner)) kind = Custody.OwnerKind.NPC;
        if (knownOwnerKinds.putIfAbsent(owner, kind) == null) setDirty();
    }

    /** Called only from HumanNpc.die. Reappearance or another remember call cannot undo this record. */
    public void died(UUID owner, long time) {
        if (knownOwnerKinds.put(owner, Custody.OwnerKind.NPC) != Custody.OwnerKind.NPC) setDirty();
        if (deadNpcOwners.putIfAbsent(owner, time) == null) setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var known = new ListTag();
        knownOwnerKinds.forEach((owner, kind) -> {
            var value = new CompoundTag(); value.putUUID("Owner", owner);
            value.putString("Kind", kind == Custody.OwnerKind.PLAYER ? "player" : "npc"); known.add(value);
        });
        var dead = new ListTag();
        deadNpcOwners.forEach((owner, time) -> {
            var value = new CompoundTag(); value.putUUID("Owner", owner); value.putLong("DiedAt", time); dead.add(value);
        });
        var sites = new ListTag();
        plants.forEach((site, boundary) -> {
            var value = new CompoundTag(); value.putUUID("Site", site);
            value.putString("Dimension", boundary.dimension().toString());
            value.putLong("Min", boundary.min().asLong()); value.putLong("Max", boundary.max().asLong());
            sites.add(value);
        });
        tag.put("KnownOwnerKinds", known); tag.put("DeadNpcOwners", dead); tag.put("PlantSites", sites); return tag;
    }

    public static CustodyData load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new CustodyData();
        for (var item : tag.getList("KnownOwnerKinds", Tag.TAG_COMPOUND)) {
            var value = (CompoundTag)item;
            if (!value.hasUUID("Owner")) continue;
            var kind = switch (value.getString("Kind")) {
                case "player" -> Custody.OwnerKind.PLAYER;
                case "npc" -> Custody.OwnerKind.NPC;
                default -> Custody.OwnerKind.UNKNOWN;
            };
            if (kind != Custody.OwnerKind.UNKNOWN) result.knownOwnerKinds.put(value.getUUID("Owner"), kind);
        }
        for (var item : tag.getList("DeadNpcOwners", Tag.TAG_COMPOUND)) {
            var value = (CompoundTag)item;
            if (!value.hasUUID("Owner")) continue;
            UUID owner = value.getUUID("Owner");
            result.deadNpcOwners.put(owner, value.getLong("DiedAt")); result.knownOwnerKinds.put(owner, Custody.OwnerKind.NPC);
        }
        for (var item : tag.getList("PlantSites", Tag.TAG_COMPOUND)) {
            var value = (CompoundTag)item;
            var dimension = ResourceLocation.tryParse(value.getString("Dimension"));
            if (value.hasUUID("Site") && dimension != null && value.contains("Min", Tag.TAG_LONG) && value.contains("Max", Tag.TAG_LONG))
                result.plants.put(value.getUUID("Site"), new PlantBoundary(dimension, BlockPos.of(value.getLong("Min")), BlockPos.of(value.getLong("Max"))));
        }
        return result;
    }
}
