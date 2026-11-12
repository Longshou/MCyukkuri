package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

/**
 * Register of the pet shops (docs/FEATURES.md): which shopkeeper runs a shop, where it stands, and which individuals are on its shelves.
 * The shopkeeper's UUID is the shop's identity (it already keys the goods shelves and the daily trades in {@link ShopStock}); the individuals' own custody
 * data (Owner = shopkeeper, Use = stock) is what the sale checks, and this register is the shop-side record of the same binding, so a shop can be listed,
 * audited and cleaned up. A shopkeeper enters the register when it first ticks (world generation, template or egg) and leaves it when it dies.
 * Server only; the overworld data storage holds it for every dimension.
 */
public final class ShopRegistry extends SavedData {
    private static final String NAME = "mcyukkuri_shop_register";
    private static final String CORNER = "mcy_shop_corner";
    record Deposit(UUID buyer, int price, int paid) {}
    public static final class Entry {
        public final UUID keeper;
        public final String dimension;
        public final BlockPos pos;
        public final Set<UUID> stock = new LinkedHashSet<>();
        final Map<UUID, Deposit> deposits = new LinkedHashMap<>();
        private BlockPos first, second;
        long nextRestock;
        Entry(UUID keeper, String dimension, BlockPos pos) { this.keeper = keeper; this.dimension = dimension; this.pos = pos; }
        public AABB bounds() {
            return first == null || second == null ? new AABB(pos).inflate(24, 6, 24) : AABB.encapsulatingFullBlocks(first, second);
        }
    }
    private final Map<UUID, Entry> shops = new LinkedHashMap<>();

    public static ShopRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShopRegistry::new, ShopRegistry::load), NAME);
    }

    /** Idempotent: the shop of this shopkeeper, created at the shopkeeper's post on the first call. */
    public Entry register(HumanNpc keeper) {
        var shop = shops.computeIfAbsent(keeper.getUUID(), id -> {
            setDirty();
            return new Entry(id, keeper.level().dimension().location().toString(), keeper.home().immutable());
        });
        if (shop.first == null && keeper.level() instanceof ServerLevel level) {
            var corners = level.getEntitiesOfClass(Marker.class, new AABB(shop.pos).inflate(25, 16, 25), m -> m.getTags().contains(CORNER));
            if (corners.size() == 2) {
                shop.first = corners.getFirst().blockPosition();
                shop.second = corners.getLast().blockPosition();
                setDirty();
            }
        }
        return shop;
    }

    public Entry of(HumanNpc staff) {
        if (!(staff.level() instanceof ServerLevel level) || !staff.shopStaff()) return null;
        if (staff.job() == HumanNpc.Job.SHOPKEEPER) return register(staff);
        var nearby = level.getEntitiesOfClass(HumanNpc.class, new AABB(staff.home()).inflate(40),
                n -> n.isAlive() && n.job() == HumanNpc.Job.SHOPKEEPER);
        for (var clerk : nearby) register(clerk);
        var shop = nearest(level, staff.home(), 40);
        return shop != null && shop.bounds().contains(staff.home().getCenter()) ? shop : null;
    }

    public boolean protectedAt(ServerLevel level, BlockPos pos) {
        String dim = level.dimension().location().toString();
        return shops.values().stream().anyMatch(shop -> shop.first != null && shop.dimension.equals(dim)
                && shop.bounds().contains(pos.getCenter()));
    }

    boolean reserve(Entry shop, YukkuriEntity animal, net.minecraft.server.level.ServerPlayer buyer, int price, int paid) {
        if (shop.deposits.containsKey(animal.getUUID()) || ShopMenu.count(buyer, net.minecraft.world.item.Items.EMERALD) < paid) return false;
        ShopMenu.debit(buyer, net.minecraft.world.item.Items.EMERALD, paid);
        shop.deposits.put(animal.getUUID(), new Deposit(buyer.getUUID(), price, paid));
        setDirty();
        return true;
    }

    void complete(Entry shop, UUID animal) {
        if (shop.deposits.remove(animal) != null) setDirty();
        sold(shop.keeper, animal);
    }
    /** An individual went onto this shopkeeper's shelves (bound at placement, or bought back from a player). */
    public void stocked(HumanNpc keeper, YukkuriEntity animal) {
        if (register(keeper).stock.add(animal.getUUID())) setDirty();
    }
    /** An individual left the shelves (sold or gone). */
    public void sold(UUID keeper, UUID animal) {
        var shop = shops.get(keeper);
        if (shop != null && shop.stock.remove(animal)) setDirty();
    }
    /** The shopkeeper is gone for good: the shop closes. */
    public void close(UUID keeper) { if (shops.remove(keeper) != null) setDirty(); }
    /**
     * Playground field clear only (command/PlaygroundTools): shops whose keeper was removed or whose post stands in {@code box} leave
     * the register (a discarded keeper is not dead, so {@link #close} never runs for it). Returns the forgotten keepers.
     */
    public Set<UUID> forget(ServerLevel level, AABB box, Set<UUID> removed) {
        String dimension = level.dimension().location().toString();
        var gone = new LinkedHashSet<UUID>();
        for (var shop : shops.values())
            if (removed.contains(shop.keeper) || shop.dimension.equals(dimension)
                    && box.contains(shop.pos.getX() + .5, shop.pos.getY() + .5, shop.pos.getZ() + .5)) gone.add(shop.keeper);
        if (shops.keySet().removeAll(gone)) setDirty();
        return gone;
    }
    public Entry shop(UUID keeper) { return shops.get(keeper); }
    public List<Entry> shops() { return new ArrayList<>(shops.values()); }

    /** The shop whose post is nearest to the position, within the given distance, in the same dimension. */
    public Entry nearest(ServerLevel level, BlockPos at, double range) {
        Entry best = null; double bestDistance = range * range; String dim = level.dimension().location().toString();
        for (var shop : shops.values()) {
            double d = shop.pos.distSqr(at);
            if (shop.dimension.equals(dim) && d <= bestDistance) { best = shop; bestDistance = d; }
        }
        return best;
    }
    /** Shelf individuals that are still alive, still owned by the shopkeeper and still on sale (stock the shopkeeper holds but the register missed are added). */
    public List<YukkuriEntity> liveStock(ServerLevel level, Entry shop) {
        var out = level.getEntitiesOfClass(YukkuriEntity.class, shop.bounds(), y -> y.isAlive()
                && shop.bounds().contains(y.position()) && ShopMenu.stockOf(y, shop.keeper));
        for (var y : out) if (shop.stock.add(y.getUUID())) setDirty();
        return out;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var shop : shops.values()) {
            var c = new CompoundTag(); c.putUUID("Keeper", shop.keeper); c.putString("Dim", shop.dimension); c.put("Pos", NbtUtils.writeBlockPos(shop.pos));
            c.putLong("NextRestock", shop.nextRestock);
            if (shop.first != null && shop.second != null) {
                c.put("First", NbtUtils.writeBlockPos(shop.first)); c.put("Second", NbtUtils.writeBlockPos(shop.second));
            }
            var deposits = new ListTag();
            shop.deposits.forEach((id, d) -> {
                var e = new CompoundTag();
                e.putUUID("Animal", id); e.putUUID("Buyer", d.buyer()); e.putInt("Price", d.price()); e.putInt("Paid", d.paid());
                deposits.add(e);
            });
            c.put("Deposits", deposits);
            var stock = new ListTag();
            for (var id : shop.stock) { var e = new CompoundTag(); e.putUUID("Id", id); stock.add(e); }
            c.put("Stock", stock); list.add(c);
        }
        tag.put("Shops", list); return tag;
    }
    public static ShopRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new ShopRegistry();
        for (var item : tag.getList("Shops", 10)) {
            var c = (CompoundTag) item;
            if (!c.hasUUID("Keeper")) continue;
            var pos = NbtUtils.readBlockPos(c, "Pos").orElse(BlockPos.ZERO);
            var shop = new Entry(c.getUUID("Keeper"), c.getString("Dim"), pos);
            shop.nextRestock = c.getLong("NextRestock");
            shop.first = NbtUtils.readBlockPos(c, "First").orElse(null);
            shop.second = NbtUtils.readBlockPos(c, "Second").orElse(null);
            for (var raw : c.getList("Deposits", 10)) {
                var e = (CompoundTag) raw;
                if (e.hasUUID("Animal") && e.hasUUID("Buyer") && e.getInt("Paid") > 0 && e.getInt("Price") >= e.getInt("Paid"))
                    shop.deposits.put(e.getUUID("Animal"), new Deposit(e.getUUID("Buyer"), e.getInt("Price"), e.getInt("Paid")));
            }
            for (var s : c.getList("Stock", 10)) { var e = (CompoundTag) s; if (e.hasUUID("Id")) shop.stock.add(e.getUUID("Id")); }
            result.shops.put(shop.keeper, shop);
        }
        return result;
    }
}
