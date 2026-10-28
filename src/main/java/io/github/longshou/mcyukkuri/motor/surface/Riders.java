package io.github.longshou.mcyukkuri.motor.surface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;

/**
 * The host side of a body's attachment: rider UUID to local spot (hat units) and yaw. This is one of the two facts
 * that make up an attachment (the vanilla vehicle relation is the other); there is no other stored copy (spec 5.1).
 *
 * <p>NBT is the {@code Riders} list of spec 12: {@code {UUID:int[4], X, Y, Z, Yaw}}. The sync form is one compound keyed
 * by rider entity id, with the same fields and no UUID, because the client knows the entity the id names.
 */
public final class Riders {
    /** One rider's place on the host: local hat units and yaw relative to the host frame. */
    public record Spot(Vec3 local, float yaw) {
        public Spot {
            Objects.requireNonNull(local, "local");
        }
    }

    private static final String UUID = "UUID";
    private static final String X = "X";
    private static final String Y = "Y";
    private static final String Z = "Z";
    private static final String YAW = "Yaw";

    private final Map<UUID, Spot> spots = new LinkedHashMap<>();
    private boolean dirty;

    /** Writes a spot and marks the table for a sync pass. */
    public void put(UUID rider, Vec3 local, float yaw) {
        if (rider == null || local == null) return;
        spots.put(rider, new Spot(local, yaw));
        dirty = true;
    }

    public Spot remove(UUID rider) {
        Spot removed = spots.remove(rider);
        if (removed != null) dirty = true;
        return removed;
    }

    /** The spot, or null when the table has no entry for this rider. */
    public Spot spot(UUID rider) {
        return rider == null ? null : spots.get(rider);
    }

    /** The local spot, or {@link Vec3#ZERO} when the entry is missing (that missing key is an I2 violation). */
    public Vec3 local(UUID rider) {
        Spot spot = spot(rider);
        return spot == null ? Vec3.ZERO : spot.local();
    }

    public float yaw(UUID rider) {
        Spot spot = spot(rider);
        return spot == null ? 0f : spot.yaw();
    }

    public boolean contains(UUID rider) { return rider != null && spots.containsKey(rider); }

    /** A snapshot of the keys; safe to iterate while the host adds or removes passengers. */
    public Set<UUID> keys() { return Set.copyOf(spots.keySet()); }

    public int size() { return spots.size(); }

    public boolean isEmpty() { return spots.isEmpty(); }

    /** True when a spot changed since the last {@link #clearDirty}; the host syncs at most once per tick. */
    public boolean dirty() { return dirty; }

    public void clearDirty() { dirty = false; }

    /** The {@code Riders} list of spec 12. */
    public ListTag save() {
        ListTag list = new ListTag();
        for (var entry : spots.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putIntArray(UUID, UUIDUtil.uuidToIntArray(entry.getKey()));
            tag.putDouble(X, entry.getValue().local().x);
            tag.putDouble(Y, entry.getValue().local().y);
            tag.putDouble(Z, entry.getValue().local().z);
            tag.putFloat(YAW, entry.getValue().yaw());
            list.add(tag);
        }
        return list;
    }

    /** Reads the {@code Riders} list back; entries without a usable UUID are skipped. */
    public void load(ListTag list) {
        spots.clear();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                int[] raw = tag.getIntArray(UUID);
                if (raw.length != 4) continue;
                spots.put(UUIDUtil.uuidFromIntArray(raw), readSpot(tag));
            }
        }
        dirty = true;
    }

    /** The sync form: one compound keyed by rider entity id. Riders whose entity id is unknown are skipped. */
    public CompoundTag syncTag(Function<UUID, Integer> entityIdOf) {
        CompoundTag root = new CompoundTag();
        for (var entry : spots.entrySet()) {
            Integer id = entityIdOf.apply(entry.getKey());
            if (id == null) continue;
            CompoundTag tag = new CompoundTag();
            tag.putDouble(X, entry.getValue().local().x);
            tag.putDouble(Y, entry.getValue().local().y);
            tag.putDouble(Z, entry.getValue().local().z);
            tag.putFloat(YAW, entry.getValue().yaw());
            root.put(Integer.toString(id), tag);
        }
        return root;
    }

    /** The client side of {@link #syncTag}: rider entity id to spot. */
    public static Map<Integer, Spot> readSync(CompoundTag root) {
        Map<Integer, Spot> out = new LinkedHashMap<>();
        if (root == null) return out;
        for (String key : root.getAllKeys()) {
            int id;
            try { id = Integer.parseInt(key); } catch (NumberFormatException ex) { continue; }
            out.put(id, readSpot(root.getCompound(key)));
        }
        return out;
    }

    private static Spot readSpot(CompoundTag tag) {
        return new Spot(new Vec3(tag.getDouble(X), tag.getDouble(Y), tag.getDouble(Z)), tag.getFloat(YAW));
    }
}
