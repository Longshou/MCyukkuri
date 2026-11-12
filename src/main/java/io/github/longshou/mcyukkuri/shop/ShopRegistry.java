package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Register of the pet shops (docs/FEATURES.md, 宠物店). A shop is keyed by the UUID of the staff member who owns its stock (the template's
 * {@code shopkeeper} post; the player sees no difference between staff). The template's marker entities give the footprint (two corners), the
 * counter cell where bought animals are handed over, the arrivals barrel and the waste barrel; the item-frame markers become fixed vanilla frames
 * the first time the shop registers. Deposits of reservations and refunds owed for reserved animals that died are saved here. The sale roster
 * itself is never stored: it is every stock animal physically inside the footprint ({@link #liveStock}). Server only; the overworld storage
 * holds it for every dimension.
 */
public final class ShopRegistry extends SavedData {
    private static final String NAME = "mcyukkuri_shop_register";
    static final String OWNER_ROLE = "shopkeeper";
    private static final String CORNER = "mcy_shop_corner", COUNTER = "mcy_shop_counter", DOCK = "mcy_shop_dock", TRASH = "mcy_shop_trash",
            FRAME = "mcy_shop_frame";
    /** Footprint around the owner's post when a shop has no authored corners (a staff member placed by egg or command). */
    private static final int LOOSE_RADIUS = 12;
    record Deposit(UUID buyer, int price, int paid) {}
    public static final class Entry {
        public final UUID keeper;
        public final String dimension;
        public final BlockPos pos;
        private BlockPos first, second, counter, dock, trash;
        long nextRestock;
        final Map<UUID, Deposit> deposits = new LinkedHashMap<>();
        final Map<UUID, Integer> refunds = new LinkedHashMap<>();
        Entry(UUID keeper, String dimension, BlockPos pos) { this.keeper = keeper; this.dimension = dimension; this.pos = pos; }
        public AABB bounds() {
            return first == null ? new AABB(pos).inflate(LOOSE_RADIUS, 4, LOOSE_RADIUS) : AABB.encapsulatingFullBlocks(first, second);
        }
        public boolean contains(Vec3 at) { return bounds().contains(at); }
        /** The staff side of the counter: where a bought animal is handed over. */
        public BlockPos counter() { return counter == null ? pos : counter; }
        BlockPos dock() { return dock; }
        BlockPos trash() { return trash; }
        public boolean authored() { return first != null; }
        Deposit deposit(UUID animal) { return deposits.get(animal); }
        long nextRestock() { return nextRestock; }
    }
    private final Map<UUID, Entry> shops = new LinkedHashMap<>();

    public static ShopRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShopRegistry::new, ShopRegistry::load), NAME);
    }

    /** Idempotent: the shop owned by this staff member, created at its post on the first call, its markers read once. */
    public Entry register(HumanNpc owner) {
        var shop = shops.computeIfAbsent(owner.getUUID(), id -> {
            setDirty();
            return new Entry(id, owner.level().dimension().location().toString(), owner.home().immutable());
        });
        if (shop.first == null && owner.level() instanceof ServerLevel level) readMarkers(level, shop);
        return shop;
    }

    /** The shop a staff member works in: its own (the owner post, or a lone staff member), else the shop whose footprint holds its post. */
    public Entry of(HumanNpc staff) {
        if (!(staff.level() instanceof ServerLevel level) || staff.job() != HumanNpc.Job.SHOPKEEPER) return null;
        if (staff.siteRole().isEmpty() || staff.siteRole().equals(OWNER_ROLE)) return register(staff);
        var shop = shopAt(level, staff.home().getCenter());
        if (shop != null) return shop;
        for (var owner : level.getEntitiesOfClass(HumanNpc.class, new AABB(staff.home()).inflate(32),
                n -> n.isAlive() && n.job() == HumanNpc.Job.SHOPKEEPER && n.siteRole().equals(OWNER_ROLE))) register(owner);
        return shopAt(level, staff.home().getCenter());
    }

    /** The shop whose footprint holds this point, in this dimension. */
    public Entry shopAt(ServerLevel level, Vec3 at) {
        String dim = level.dimension().location().toString();
        for (var shop : shops.values()) if (shop.dimension.equals(dim) && shop.contains(at)) return shop;
        return null;
    }

    /** Inside an authored shop footprint: no player breaks, places or uses anything there (ShopProtection). */
    public boolean protectedAt(ServerLevel level, BlockPos pos) {
        var shop = shopAt(level, pos.getCenter());
        return shop != null && shop.authored();
    }

    /** Every stock animal of this shop that is physically inside its footprint: the sale roster. */
    public static List<YukkuriEntity> liveStock(ServerLevel level, Entry shop) {
        return level.getEntitiesOfClass(YukkuriEntity.class, shop.bounds(), y -> y.isAlive() && shop.contains(y.position())
                && ShopTrade.stockOf(y, shop.keeper));
    }

    private void readMarkers(ServerLevel level, Entry shop) {
        var markers = level.getEntitiesOfClass(Marker.class, new AABB(shop.pos).inflate(32, 16, 32), m -> m.getTags().stream().anyMatch(t -> t.startsWith("mcy_shop_")));
        var corners = new ArrayList<BlockPos>();
        for (var m : markers) {
            var at = m.blockPosition();
            if (m.getTags().contains(CORNER)) corners.add(at);
            else if (m.getTags().contains(COUNTER)) shop.counter = at;
            else if (m.getTags().contains(DOCK)) shop.dock = at;
            else if (m.getTags().contains(TRASH)) shop.trash = at;
        }
        if (corners.size() != 2) return;
        shop.first = corners.getFirst(); shop.second = corners.getLast();
        for (var m : markers) if (m.getTags().contains(FRAME) && shop.contains(m.position())) hangFrame(level, m);
        GameplayLog.world(level, "shop", "action=register shop={} corners={}..{} counter={} dock={} trash={}", shop.keeper,
                shop.first.toShortString(), shop.second.toShortString(), shop.counter, shop.dock, shop.trash);
        setDirty();
    }

    /** A fixed vanilla item frame on the wall behind the marker's cell, showing the marker's good; the marker is used up. */
    private static void hangFrame(ServerLevel level, Marker marker) {
        var at = marker.blockPosition();
        Direction facing = null;
        for (var d : Direction.Plane.HORIZONTAL)
            if (level.getBlockState(at.relative(d.getOpposite())).isSolid() && !level.getBlockState(at.relative(d)).isSolid()) { facing = d; break; }
        var data = marker.saveWithoutId(new CompoundTag()).getCompound("data");
        if (facing == null || !data.contains("Item", 10)) return;
        var tag = new CompoundTag();
        tag.putString("id", "minecraft:item_frame");
        var pos = new ListTag(); pos.add(DoubleTag.valueOf(at.getX() + .5)); pos.add(DoubleTag.valueOf(at.getY() + .5)); pos.add(DoubleTag.valueOf(at.getZ() + .5));
        tag.put("Pos", pos);
        tag.putInt("TileX", at.getX()); tag.putInt("TileY", at.getY()); tag.putInt("TileZ", at.getZ());
        tag.putByte("Facing", (byte) facing.get3DDataValue());
        tag.put("Item", data.getCompound("Item"));
        tag.putBoolean("Fixed", true);
        tag.putBoolean("Invulnerable", true);
        var frame = EntityType.loadEntityRecursive(tag, level, e -> e);
        if (frame != null && level.addFreshEntity(frame)) marker.discard();
    }

    // ---- reservations ------------------------------------------------------------------------------------------------

    void reserve(Entry shop, UUID animal, UUID buyer, int price, int paid) { shop.deposits.put(animal, new Deposit(buyer, price, paid)); setDirty(); }
    void sold(Entry shop, UUID animal) { if (shop.deposits.remove(animal) != null) setDirty(); }
    /** A reserved animal that is gone (died, or left the shop other than by its sale): its deposit is owed back to the buyer. */
    void refund(Entry shop, UUID animal) {
        var deposit = shop.deposits.remove(animal);
        if (deposit == null) return;
        shop.refunds.merge(deposit.buyer(), deposit.paid(), Integer::sum);
        setDirty();
    }
    /** Deposits owed to this player, paid out now (the menu opening). */
    int collectRefund(Entry shop, UUID player) {
        Integer owed = shop.refunds.remove(player);
        if (owed != null) setDirty();
        return owed == null ? 0 : owed;
    }
    void restocked(Entry shop, long next) { shop.nextRestock = next; setDirty(); }

    /** The owning staff member is gone for good: the shop closes. */
    public void close(UUID keeper) { if (shops.remove(keeper) != null) setDirty(); }
    /**
     * Playground field clear only (command/PlaygroundTools): shops whose owner was removed or whose post stands in {@code box} leave
     * the register (a discarded owner is not dead, so {@link #close} never runs for it). Returns the forgotten owners.
     */
    public Set<UUID> forget(ServerLevel level, AABB box, Set<UUID> removed) {
        String dimension = level.dimension().location().toString();
        var gone = new LinkedHashSet<UUID>();
        for (var shop : shops.values())
            if (removed.contains(shop.keeper) || shop.dimension.equals(dimension) && box.contains(shop.pos.getCenter())) gone.add(shop.keeper);
        if (shops.keySet().removeAll(gone)) setDirty();
        return gone;
    }
    public Entry shop(UUID keeper) { return shops.get(keeper); }
    public List<Entry> shops() { return new ArrayList<>(shops.values()); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var shop : shops.values()) {
            var c = new CompoundTag(); c.putUUID("Keeper", shop.keeper); c.putString("Dim", shop.dimension); c.put("Pos", NbtUtils.writeBlockPos(shop.pos));
            c.putLong("NextRestock", shop.nextRestock);
            if (shop.first != null) { c.put("First", NbtUtils.writeBlockPos(shop.first)); c.put("Second", NbtUtils.writeBlockPos(shop.second)); }
            if (shop.counter != null) c.put("Counter", NbtUtils.writeBlockPos(shop.counter));
            if (shop.dock != null) c.put("Dock", NbtUtils.writeBlockPos(shop.dock));
            if (shop.trash != null) c.put("Trash", NbtUtils.writeBlockPos(shop.trash));
            var deposits = new ListTag();
            shop.deposits.forEach((id, d) -> {
                var e = new CompoundTag();
                e.putUUID("Animal", id); e.putUUID("Buyer", d.buyer()); e.putInt("Price", d.price()); e.putInt("Paid", d.paid());
                deposits.add(e);
            });
            c.put("Deposits", deposits);
            var refunds = new ListTag();
            shop.refunds.forEach((player, owed) -> { var e = new CompoundTag(); e.putUUID("Player", player); e.putInt("Owed", owed); refunds.add(e); });
            c.put("Refunds", refunds);
            list.add(c);
        }
        tag.put("Shops", list); return tag;
    }
    public static ShopRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new ShopRegistry();
        for (var item : tag.getList("Shops", 10)) {
            var c = (CompoundTag) item;
            if (!c.hasUUID("Keeper")) continue;
            var shop = new Entry(c.getUUID("Keeper"), c.getString("Dim"), NbtUtils.readBlockPos(c, "Pos").orElse(BlockPos.ZERO));
            shop.nextRestock = c.getLong("NextRestock");
            shop.first = NbtUtils.readBlockPos(c, "First").orElse(null);
            shop.second = shop.first == null ? null : NbtUtils.readBlockPos(c, "Second").orElse(null);
            if (shop.second == null) shop.first = null;
            shop.counter = NbtUtils.readBlockPos(c, "Counter").orElse(null);
            shop.dock = NbtUtils.readBlockPos(c, "Dock").orElse(null);
            shop.trash = NbtUtils.readBlockPos(c, "Trash").orElse(null);
            for (var raw : c.getList("Deposits", 10)) {
                var e = (CompoundTag) raw;
                if (e.hasUUID("Animal") && e.hasUUID("Buyer") && e.getInt("Paid") > 0 && e.getInt("Price") >= e.getInt("Paid"))
                    shop.deposits.put(e.getUUID("Animal"), new Deposit(e.getUUID("Buyer"), e.getInt("Price"), e.getInt("Paid")));
            }
            for (var raw : c.getList("Refunds", 10)) {
                var e = (CompoundTag) raw;
                if (e.hasUUID("Player") && e.getInt("Owed") > 0) shop.refunds.put(e.getUUID("Player"), e.getInt("Owed"));
            }
            result.shops.put(shop.keeper, shop);
        }
        return result;
    }
}
