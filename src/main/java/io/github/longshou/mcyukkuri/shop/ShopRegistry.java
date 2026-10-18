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
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Register of the pet shops (docs/HUMAN_WORLD.md "Shop register"): which shopkeeper runs a shop, where it stands, and which individuals are on its shelves.
 * The shopkeeper's UUID is the shop's identity (it already keys the goods shelves and the daily trades in {@link ShopStock}); the individuals' own custody
 * data (Owner = shopkeeper, Use = stock) is what the sale checks, and this register is the shop-side record of the same binding, so a shop can be listed,
 * audited and cleaned up. A shopkeeper enters the register when it first ticks (world generation, template or egg) and leaves it when it dies.
 * Server only; the overworld data storage holds it for every dimension.
 */
public final class ShopRegistry extends SavedData {
    private static final String NAME = "mcyukkuri_shop_register";
    public static final class Entry {
        public final UUID keeper;
        public final String dimension;
        public final BlockPos pos;
        public final Set<UUID> stock = new LinkedHashSet<>();
        Entry(UUID keeper, String dimension, BlockPos pos) { this.keeper = keeper; this.dimension = dimension; this.pos = pos; }
    }
    private final Map<UUID, Entry> shops = new LinkedHashMap<>();

    public static ShopRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShopRegistry::new, ShopRegistry::load), NAME);
    }

    /** Idempotent: the shop of this shopkeeper, created at the shopkeeper's post on the first call. */
    public Entry register(HumanNpc keeper) {
        return shops.computeIfAbsent(keeper.getUUID(), id -> {
            setDirty();
            return new Entry(id, keeper.level().dimension().location().toString(), keeper.home().immutable());
        });
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
    public List<YukkuriEntity> liveStock(ServerLevel level, HumanNpc keeper) {
        var shop = register(keeper); var out = keeper.animals(Custody.Role.STOCK);
        for (var y : out) if (shop.stock.add(y.getUUID())) setDirty();
        return out;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var shop : shops.values()) {
            var c = new CompoundTag(); c.putUUID("Keeper", shop.keeper); c.putString("Dim", shop.dimension); c.put("Pos", NbtUtils.writeBlockPos(shop.pos));
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
            for (var s : c.getList("Stock", 10)) { var e = (CompoundTag) s; if (e.hasUUID("Id")) shop.stock.add(e.getUUID("Id")); }
            result.shops.put(shop.keeper, shop);
        }
        return result;
    }
}
