package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Delivery orders (docs/PET_SHOP.md): every game day each shopkeeper wants three things from a fixed pool, drawn from the shopkeeper UUID and
 * the day, so everybody sees the same three. A player can deliver each of them once a day for the retail value of the goods (no bonus, so buying from the
 * shop and delivering back is never a gain). Delivered goods refill the shelf up to its capacity, which closes the loop farm, shelf, other players.
 * This is game design; the wiki has no such service. Server logic, pool data readable on both sides.
 */
public final class ShopOrders {
    public static final int PER_DAY = 3;
    /** good: id of the catalog good whose shelf the delivery fills (units = count / units per pack), or empty for a plain buy-in. */
    public record Order(String id, Supplier<Item> item, int count, int reward, String good) {}
    private static Order order(String id, Supplier<Item> item, int count, int reward, String good) { return new Order(id, item, count, reward, good); }

    public static final List<Order> POOL = List.of(
        order("regular_grain", () -> ModItems.GRAIN_REGULAR.get(), 16, 2, "oil_grain_regular"),
        order("budget_grain", () -> ModItems.GRAIN_BUDGET.get(), 32, 4, "oil_grain_budget"),
        order("cookie", () -> Items.COOKIE, 8, 4, "cookie"),
        order("sweet_candy", () -> ModItems.SWEET_CANDY.get(), 18, 3, "sweet_candy"),
        order("yarn_ball", () -> ModItems.TOY.get(), 2, 4, "yarn_ball"),
        order("wash_cloth", () -> ModItems.WASH_CLOTH.get(), 1, 2, "wash_cloth"),
        order("wheat", () -> Items.WHEAT, 32, 2, ""),
        order("carrot", () -> Items.CARROT, 16, 2, ""));

    private ShopOrders() {}

    /** Pool indexes of today three orders for this shopkeeper. */
    public static int[] today(ServerLevel level, UUID shopId) {
        long seed = shopId.getMostSignificantBits() * 31 + shopId.getLeastSignificantBits() + ShopStock.today(level) * 0x9E3779B97F4A7C15L;
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < POOL.size(); i++) all.add(i);
        Collections.shuffle(all, new Random(seed));
        return new int[] {all.get(0), all.get(1), all.get(2)};
    }
    public static Order order(int pool) { return POOL.get(Math.floorMod(pool, POOL.size())); }
    /** One item of the order for the screen icon. */
    public static ItemStack icon(int pool) { return new ItemStack(order(pool).item().get()); }

    private static int count(Player p, Item item) { return p.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void debit(Player p, Item item, int amount) {
        for (ItemStack stack : p.getInventory().items) if (stack.is(item)) { int used = Math.min(amount, stack.getCount()); stack.shrink(used); amount -= used; if (amount == 0) return; }
    }

    /** Delivers today order in this slot (0..2): takes the goods, pays the reward, refills the shelf. False when done already or not enough goods. */
    public static boolean deliver(ServerLevel level, UUID shopId, Player player, int slot) {
        if (slot < 0 || slot >= PER_DAY) {
            GameplayLog.event(player,"shop","action=deliver_order result=refused shop={} slot={} reason=invalid_slot",shopId,slot);
            return false;
        }
        var stock = ShopStock.get(level);
        if ((stock.ordersDone(level, shopId, player.getUUID()) >> slot & 1) != 0) {
            GameplayLog.event(player,"shop","action=deliver_order result=refused shop={} slot={} reason=already_done",shopId,slot);
            return false;
        }
        var order = order(today(level, shopId)[slot]); Item item = order.item().get();
        if (count(player, item) < order.count()) {
            GameplayLog.event(player,"shop","action=deliver_order result=refused shop={} slot={} reason=insufficient_goods item={} required={} held={}",shopId,slot,item,order.count(),count(player,item));
            return false;
        }
        debit(player, item, order.count());
        for (int left = order.reward(); left > 0; left -= 64) {
            var pay = new ItemStack(Items.EMERALD, Math.min(64, left));
            if (!player.getInventory().add(pay)) player.drop(pay, false);
        }
        int index = ShopCatalog.indexOf(order.good());
        if (index >= 0) {
            var good = ShopCatalog.at(index);
            stock.add(level, shopId, good, order.count() / Math.max(1, good.stack().getCount()));
        }
        stock.orderDone(level, shopId, player.getUUID(), slot);
        GameplayLog.event(player,"shop","action=deliver_order result=committed shop={} slot={} item={} count_used={} emeralds_received={}",shopId,slot,item,order.count(),order.reward());
        return true;
    }
}
