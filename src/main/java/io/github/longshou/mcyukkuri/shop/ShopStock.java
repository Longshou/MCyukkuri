package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.human.HumanWorld;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Shelf stock of every shopkeeper's goods and what each player has bought or delivered today (docs/PET_SHOP.md section 12). Keyed by the shopkeeper's UUID.
 * Restocking is applied lazily when a shelf is looked at: each elapsed game day adds the good's daily restock up to its capacity, so nothing ticks and
 * nothing depends on whether the shop was loaded. A new shopkeeper starts with full shelves. Server only.
 */
public final class ShopStock extends SavedData {
    public static final long DAY = 24000L;
    private static final String NAME = "mcyukkuri_shop_stock";
    /** Shelves restock at most this many days at once (a long absence does not matter beyond capacity anyway). */
    private static final int MAX_CATCH_UP = 30;
    private static final class Visit { int day = -1, trades, orders; }
    private static final class Shop {
        int day = -1;
        final Map<String, Integer> stock = new HashMap<>();
        final Map<UUID, Visit> visits = new HashMap<>();
    }
    private final Map<UUID, Shop> shops = new HashMap<>();

    public static ShopStock get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShopStock::new, ShopStock::load), NAME);
    }
    public static int today(ServerLevel level) { return (int)(level.getGameTime() / DAY); }

    private Shop shop(ServerLevel level, UUID id) {
        Shop shop = shops.computeIfAbsent(id, k -> new Shop());
        int today = today(level);
        if (shop.day < 0) {
            for (var good : ShopCatalog.GOODS) shop.stock.put(good.id(), good.cap());
            shop.day = today; setDirty();
        } else if (shop.day < today) {
            int days = Math.min(today - shop.day, MAX_CATCH_UP);
            for (var good : ShopCatalog.GOODS) { Integer old = shop.stock.get(good.id()); shop.stock.put(good.id(), old == null ? good.cap() : Math.min(good.cap(), old + good.restock() * days)); }
            shop.day = today; setDirty();
        }
        return shop;
    }
    private Visit visit(ServerLevel level, UUID shopId, UUID player) {
        Visit visit = shop(level, shopId).visits.computeIfAbsent(player, k -> new Visit());
        int today = today(level);
        if (visit.day != today) { visit.day = today; visit.trades = 0; visit.orders = 0; setDirty(); }
        return visit;
    }

    // shelves
    public int stock(ServerLevel level, UUID shopId, ShopCatalog.Good good) { return shop(level, shopId).stock.getOrDefault(good.id(), good.cap()); }
    /** Takes one unit off the shelf; false when sold out. */
    public boolean take(ServerLevel level, UUID shopId, ShopCatalog.Good good) {
        var shop = shop(level, shopId); int n = shop.stock.getOrDefault(good.id(), good.cap()); // a good added later starts with full shelves
        if (n <= 0) return false;
        shop.stock.put(good.id(), n - 1); setDirty(); return true;
    }
    /** Puts delivered units on the shelf (never above capacity); returns how many fitted. */
    public int add(ServerLevel level, UUID shopId, ShopCatalog.Good good, int units) {
        var shop = shop(level, shopId); int n = shop.stock.getOrDefault(good.id(), good.cap());
        int added = Math.max(0, Math.min(units, good.cap() - n));
        if (added > 0) { shop.stock.put(good.id(), n + added); setDirty(); }
        return added;
    }

    // purchases per player and game day (the shopkeeper's old shared eight-a-day limit, now per player)
    public int tradesLeft(ServerLevel level, UUID shopId, UUID player) { return Math.max(0, HumanWorld.TRADE_DAY_LIMIT.get() - visit(level, shopId, player).trades); }
    public void useTrade(ServerLevel level, UUID shopId, UUID player) { visit(level, shopId, player).trades++; setDirty(); }

    // delivery orders done today, one bit per order
    public int ordersDone(ServerLevel level, UUID shopId, UUID player) { return visit(level, shopId, player).orders; }
    public void orderDone(ServerLevel level, UUID shopId, UUID player, int order) { visit(level, shopId, player).orders |= 1 << order; setDirty(); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        shops.forEach((id, shop) -> {
            var c = new CompoundTag(); c.putUUID("Shop", id); c.putInt("Day", shop.day);
            var stock = new ListTag();
            shop.stock.forEach((good, n) -> { var e = new CompoundTag(); e.putString("Good", good); e.putInt("N", n); stock.add(e); });
            c.put("Stock", stock);
            var visits = new ListTag();
            shop.visits.forEach((player, v) -> {
                var e = new CompoundTag(); e.putUUID("Player", player); e.putInt("Day", v.day); e.putInt("Trades", v.trades); e.putInt("Orders", v.orders); visits.add(e);
            });
            c.put("Visits", visits); list.add(c);
        });
        tag.put("Shops", list); return tag;
    }
    public static ShopStock load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new ShopStock();
        for (var item : tag.getList("Shops", 10)) {
            var c = (CompoundTag) item;
            if (!c.hasUUID("Shop")) continue;
            var shop = new Shop(); shop.day = c.getInt("Day");
            for (var s : c.getList("Stock", 10)) { var e = (CompoundTag) s; shop.stock.put(e.getString("Good"), Math.max(0, e.getInt("N"))); }
            for (var v : c.getList("Visits", 10)) {
                var e = (CompoundTag) v;
                if (!e.hasUUID("Player")) continue;
                var visit = new Visit(); visit.day = e.getInt("Day"); visit.trades = Math.max(0, e.getInt("Trades")); visit.orders = e.getInt("Orders") & 7;
                shop.visits.put(e.getUUID("Player"), visit);
            }
            result.shops.put(c.getUUID("Shop"), shop);
        }
        return result;
    }
}
