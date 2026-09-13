package io.github.longshou.mcyukkuri.freezer;

import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Personal four-place vaults live in the SAME save as captive identities. Cabinets are access points. */
public final class FreezerStorage {
    public static final int CAPACITY = 4;
    private final YukkuriWorldData world;
    private final Map<UUID, List<UUID>> vaults = new HashMap<>();
    public FreezerStorage(YukkuriWorldData world) { this.world = world; }
    public int count(UUID owner) { return vaults.getOrDefault(owner, List.of()).size(); }
    public boolean contains(UUID token) { return vaults.values().stream().anyMatch(v -> v.contains(token)); }
    public List<CompoundTag> occupants(UUID owner) {
        return vaults.getOrDefault(owner, List.of()).stream().map(world::captiveSnapshot).toList();
    }
    public boolean store(UUID owner, UUID token) {
        if (owner == null || token == null || count(owner) >= CAPACITY || contains(token)) return false;
        UUID next = world.rotateToken(token);
        if (next == null) return false;
        vaults.computeIfAbsent(owner, id -> new ArrayList<>()).add(next);
        world.setDirty(); return true;
    }
    public UUID retrieve(UUID owner) {
        List<UUID> slots = vaults.get(owner);
        if (slots == null || slots.isEmpty()) return null;
        UUID old = slots.getFirst();
        // Remove the lease only during this synchronous server-thread transaction.
        slots.removeFirst();
        UUID next = world.rotateToken(old);
        if (next == null) { slots.addFirst(old); return null; }
        if (slots.isEmpty()) vaults.remove(owner);
        world.setDirty(); return next;
    }
    public ListTag save() {
        ListTag records = new ListTag();
        vaults.forEach((owner, slots) -> slots.forEach(token -> {
            CompoundTag record = new CompoundTag(); record.putUUID("Owner", owner); record.putUUID("Token", token); records.add(record);
        })); return records;
    }
    public void load(ListTag records) {
        vaults.clear();
        for (Tag entry : records) {
            CompoundTag record = (CompoundTag) entry;
            if (!record.hasUUID("Owner") || !record.hasUUID("Token")) continue;
            UUID token = record.getUUID("Token");
            // Preserve even over-capacity legacy records: never destroy a living record on migration.
            if (world.hasToken(token) && !contains(token)) vaults.computeIfAbsent(record.getUUID("Owner"), id -> new ArrayList<>()).add(token);
        }
    }
}
