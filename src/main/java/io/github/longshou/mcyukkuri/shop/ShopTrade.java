package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Accepted orders live in the shop register; the animal's lock keeps it in place while staff fetch it.
 * A paid purchase remains there until the customer takes the basket.
 */
public final class ShopTrade {
    public static final String SALE = "sale", HANDOVER = "handover", SELL = "sell";
    /** A buyer this far from the staff at the counter takes the basket from its hands; otherwise it is set on the counter for the buyer. */
    static final double HAND_REACH = 4.5;
    /** A buyer or seller counts as in the shop within this margin around the footprint (the pavement in front of the door included). */
    private static final double PRESENT_MARGIN = 4;
    static final String SHIP_OUT = "ShipOut", FOR_SALE_SINCE = "ForSaleSince";
    private ShopTrade() {}

    public static boolean stockOf(YukkuriEntity y, UUID keeper) { return HumanCommerce.ownedBy(y, keeper) && Custody.view(y).role() == Custody.Role.STOCK; }
    static boolean order(YukkuriEntity y) { return y.pet().busyWith().equals(SALE) || y.pet().busyWith().equals(HANDOVER); }
    /** The price still due from this buyer: the reserved price less the deposit for its reserver, else today's quote. */
    static int due(ShopRegistry.Entry shop, YukkuriEntity y) {
        var d = shop.deposit(y.getUUID());
        return d == null ? ShopPricing.price(y) : d.price() - d.paid();
    }
    /** A quarter of the price, at least one emerald. */
    static int deposit(int price) { return Math.max(1, (price + 3) / 4); }

    // ---- emeralds -----------------------------------------------------------------------------------------------------

    static int emeralds(Player p) { return p.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum(); }
    static void debit(Player p, int amount) {
        for (ItemStack stack : p.getInventory().items) if (stack.is(Items.EMERALD)) { int used = Math.min(amount, stack.getCount()); stack.shrink(used); amount -= used; if (amount == 0) return; }
    }
    static void pay(Player p, int amount) {
        for (; amount > 0; amount -= 64) { var stack = new ItemStack(Items.EMERALD, Math.min(64, amount)); if (!p.getInventory().add(stack)) p.drop(stack, false); }
    }
    static void say(Player p, String key, Object... args) { p.displayClientMessage(Component.translatable("shop.mcyukkuri." + key, args), true); }
    private static ServerPlayer present(ServerLevel level, ShopRegistry.Entry shop, UUID id) {
        var p = id == null ? null : level.getServer().getPlayerList().getPlayer(id);
        return p != null && p.level() == level && p.isAlive() && !p.isSpectator()
                && shop.bounds().inflate(PRESENT_MARGIN, 2, PRESENT_MARGIN).contains(p.position()) ? p : null;
    }

    // ---- buying ---------------------------------------------------------------------------------------------------------

    /** Why this player cannot order or reserve this animal right now, or null. These are the only refusals at the click. */
    static String buyRefusal(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, Player p, int need) {
        if (!level.isDay()) return "closed";
        if (y == null || !y.isAlive() || !shop.contains(y.position()) || !stockOf(y, shop.id)) return "gone";
        var d = shop.deposit(y.getUUID());
        if (d != null && !d.buyer().equals(p.getUUID())) return "reserved";
        if (y.pet().busy() || shop.orders.containsKey(y.getUUID())) return "ordered";
        return emeralds(p) < need ? "emeralds" : null;
    }

    /** The player orders this stock animal: it waits, glad, for the first free staff member to bring it to the counter. */
    static boolean buy(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, ServerPlayer p) {
        int need = y == null ? 0 : due(shop, y);
        String why = buyRefusal(level, shop, y, p, need);
        if (why != null) {
            say(p, "buy.refused." + why, need, emeralds(p));
            GameplayLog.event(p, "shop", "action=buy_live result=refused shop={} target={} reason={} due={} emeralds={}",
                    shop.id, y == null ? null : y.getUUID(), why, need, emeralds(p));
            return false;
        }
        y.pet().lock(SALE, p.getUUID());
        ShopRegistry.get(level).order(shop, y.getUUID(), new ShopRegistry.Order(SALE, p.getUUID(), need, null));
        y.playAction("joy_jump"); y.showReaction("happy", "shop_bought", 40); y.getLookControl().setLookAt(p, 30, 30);
        say(p, "buy.accepted", y.getDisplayName(), need);
        GameplayLog.event(y, "shop", "action=buy_live result=accepted shop={} buyer={} due={}", shop.id, p.getUUID(), need);
        return true;
    }

    /** Operator handover (command): the same fetch with no charge, the session held by a staff member of the shop (a lock holder must be present). */
    static boolean handover(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, UUID buyer) {
        if (y == null || !y.isAlive() || !shop.contains(y.position()) || !stockOf(y, shop.id) || shop.orders.containsKey(y.getUUID())
                || !y.pet().lock(HANDOVER, null)) return false;
        ShopRegistry.get(level).order(shop, y.getUUID(), new ShopRegistry.Order(HANDOVER, buyer, 0, null));
        GameplayLog.event(y, "shop", "action=buy_live result=accepted shop={} buyer=operator due=0", shop.id);
        return true;
    }

    /** What stops a waiting purchase from going on, or null. Checked every staff tick and every step before the basket. */
    static String orderProblem(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, ShopRegistry.Order order) {
        if (order.kind().equals(SELL)) return sellProblem(level, shop, y, order.customer());
        if (!y.isAlive() || !shop.contains(y.position()) || !stockOf(y, shop.id)) return "gone";
        if (order.kind().equals(HANDOVER)) return null;
        if (!level.isDay()) return "closed";
        return present(level, shop, order.customer()) == null ? "buyer_left" : null;
    }

    /** A purchase or sale that cannot go on: the animal is let go and the customer is told why; nothing was paid. */
    static void cancel(ServerLevel level, ShopRegistry.Entry shop, UUID animal, String reason) {
        var order = ShopRegistry.get(level).endOrder(shop, animal);
        if (order == null) return;
        String kind = order.kind();
        UUID customer = order.customer();
        var y = level.getEntity(animal) instanceof YukkuriEntity target ? target : null;
        if (y != null) y.pet().unlock(kind);
        boolean sale = !kind.equals(SELL);
        if (!kind.equals(HANDOVER)) ShopRegistry.get(level).tell(level, customer, (sale ? "buy" : "sell") + ".cancelled." + reason,
                y == null ? Component.translatable("shop.mcyukkuri.reserve.animal") : y.getDisplayName());
        GameplayLog.world(level, "shop", "action={} result=cancelled shop={} target={} customer={} reason={} emeralds_used=0",
                sale ? "buy_live" : "sell_live", shop.id, animal, customer, reason);
    }

    /**
     * The animal climbs into the staff member's basket: the buyer pays what is still due and owns it, in the same tick. A buyer who no
     * longer has the emeralds cancels instead. An operator handover is free and goes to {@code handoverTo}. Returns the basket token,
     * or null when the purchase did not happen.
     */
    static UUID captureSale(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, YukkuriEntity y) {
        var order = shop.orders.get(y.getUUID());
        if (order == null) return null;
        boolean operator = order.kind().equals(HANDOVER);
        UUID buyer = order.customer();
        var player = operator ? null : present(level, shop, buyer);
        int due = order.price();
        if (!operator && (player == null || emeralds(player) < due)) {
            cancel(level, shop, y.getUUID(), player == null ? "buyer_left" : "emeralds");
            return null;
        }
        UUID token = YukkuriWorldData.get(level).capture(y, () -> {
            y.pet().unlock(order.kind());
            y.life().releaseHome();
            var tag = HumanCommerce.data(y).copy(); tag.remove(SHIP_OUT); HumanCommerce.set(y, tag);
            Custody.assign(y, buyer, Custody.OwnerKind.PLAYER, Custody.Role.COMPANION);
        });
        if (token == null) {
            cancel(level, shop, y.getUUID(), "capture");
            return null;
        }
        staff.setItemInHand(InteractionHand.MAIN_HAND, CarrierContents.plain(YukkuriWorldData.get(level), token));
        var registry = ShopRegistry.get(level);
        registry.order(shop, y.getUUID(), new ShopRegistry.Order(order.kind(), buyer, due, token));
        registry.sold(shop, y.getUUID());
        if (player != null) {
            debit(player, due);
            SurvivalProgress.award(player, "pet_bought");
            say(player, "buy.paid", due, y.getDisplayName());
        }
        GameplayLog.event(staff, "shop", "action=buy_live result=committed shop={} buyer={} target={} token={} emeralds_used={}",
                shop.id, buyer == null ? "operator" : buyer, y.getUUID(), token, due);
        return token;
    }

    /** The basket reaches the counter: into the buyer's hands when it stands there, else set on the counter where only the buyer can take it. */
    static boolean handOver(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, UUID buyer, ItemStack basket, UUID token) {
        var player = buyer == null ? null : level.getServer().getPlayerList().getPlayer(buyer);
        boolean hands = player != null && player.level() == level && player.isAlive() && player.distanceTo(staff) <= HAND_REACH && player.getInventory().add(basket.copy());
        if (!hands && drop(level, buyer, counterTop(level, staff), basket.copy()) == null) return false;
        staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        if (hands || buyer == null) ShopRegistry.get(level).endOrder(shop, YukkuriWorldData.get(level).captiveIdentity(token));
        staff.swing(InteractionHand.MAIN_HAND);
        if (player != null) say(player, hands ? "buy.delivered" : "buy.on_counter");
        GameplayLog.event(staff, "shop", "action=buy_live result=delivered shop={} buyer={} token={} placement={} handoff_at={}",
                shop.id, buyer == null ? "operator" : buyer, token, hands ? "inventory" : "counter", staff.blockPosition().toShortString());
        return true;
    }

    /** The top of the counter block beside the staff member (the counter faces the customer side), else its own feet. */
    private static Vec3 counterTop(ServerLevel level, HumanNpc staff) {
        var at = staff.blockPosition();
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var c = at.relative(d);
            if (level.getBlockState(c).isSolid() && level.getBlockState(c.above()).isAir()) return Vec3.atBottomCenterOf(c.above());
        }
        return staff.position();
    }

    // ---- reservations ---------------------------------------------------------------------------------------------------

    /** The player pays a deposit to keep this animal: no one else may buy it, and the three-day restock never ships it out. */
    static boolean reserve(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, ServerPlayer p) {
        int price = y == null ? 0 : ShopPricing.price(y), paid = deposit(price);
        String why = y != null && shop.deposit(y.getUUID()) != null && shop.deposit(y.getUUID()).buyer().equals(p.getUUID()) ? "own"
                : buyRefusal(level, shop, y, p, paid);
        if (why != null) {
            say(p, "reserve.refused." + why, paid, emeralds(p));
            GameplayLog.event(p, "shop", "action=reserve result=refused shop={} target={} reason={}", shop.id, y == null ? null : y.getUUID(), why);
            return false;
        }
        debit(p, paid);
        ShopRegistry.get(level).reserve(shop, y.getUUID(), p.getUUID(), price, paid, level.getGameTime());
        var tag = HumanCommerce.data(y).copy(); tag.remove(SHIP_OUT); HumanCommerce.set(y, tag);
        y.showReaction("happy", "shop_reserved", 40);
        say(p, "reserve.done", paid, y.getDisplayName(), price - paid);
        GameplayLog.event(y, "shop", "action=reserve result=committed shop={} buyer={} price={} deposit={}", shop.id, p.getUUID(), price, paid);
        return true;
    }

    // ---- buyback --------------------------------------------------------------------------------------------------------

    /** Why the shop cannot take this companion of the player now, or null. */
    static String sellRefusal(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, Player p) {
        if (!level.isDay()) return "closed";
        if (y == null || !y.isAlive() || !y.pet().ownedBy(p.getUUID())) return "gone";
        // The shop has land pens only: no water for an aquatic Marisa, and house yukkuri are not pets.
        if (y.profile().aquatic() || y.profile().houseBody()) return "unsuited";
        if (!shop.contains(y.position())) return "outside";
        if (y.pet().busy() || shop.orders.containsKey(y.getUUID()) || y.isPassenger() || y.isVehicle()) return "busy";
        return !shop.legacy() && ShopStaff.freeNest(level, shop, SpeciesProfile.of(y.getVariant()).dangerToYukkuri(), y.position()) == null ? "no_nest" : null;
    }

    /** The player sells its companion: it waits beside the seller for a staff member with a basket; the seller is paid when it is in it. */
    static boolean sell(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, ServerPlayer p) {
        String why = sellRefusal(level, shop, y, p);
        if (why != null) {
            say(p, "sell.refused." + why);
            GameplayLog.event(p, "shop", "action=sell_live result=refused shop={} target={} reason={}", shop.id, y == null ? null : y.getUUID(), why);
            return false;
        }
        y.pet().lock(SELL, p.getUUID());
        int price = ShopPricing.buyback(y);
        ShopRegistry.get(level).order(shop, y.getUUID(), new ShopRegistry.Order(SELL, p.getUUID(), price, null));
        var pers = y.life().personality();
        y.getLookControl().setLookAt(p, 30, 30);
        // Its own numbers answer being sold: a fond one droops in tears, a proud one turns its face away, the rest bow.
        if (pers.attachment() >= 60 || pers.trust(p.getUUID()) >= BondLevels.TRUST_AT_20) { y.playAction("droop"); y.showReaction("cry", "shop_sold_sad", 80); }
        else if (pers.friendliness() <= 40) { y.playAction("ignore"); y.showReaction("smug", "shop_sold_proud", 80); }
        else y.playAction("bow");
        say(p, "sell.accepted", y.getDisplayName(), price);
        GameplayLog.event(y, "shop", "action=sell_live result=accepted shop={} seller={} offer={}", shop.id, p.getUUID(), price);
        return true;
    }

    /** What stops a waiting sale from going on, or null. */
    private static String sellProblem(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, UUID seller) {
        if (!y.isAlive() || seller == null || !y.pet().ownedBy(seller) || !shop.contains(y.position())) return "gone";
        if (!level.isDay()) return "closed";
        return present(level, shop, seller) == null ? "seller_left" : null;
    }

    /** The sold companion climbs into the staff basket: it becomes the shop's stock and the seller is paid, in the same tick. */
    static UUID captureSold(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, YukkuriEntity y) {
        var order = shop.orders.get(y.getUUID());
        if (order == null) return null;
        UUID seller = order.customer();
        var player = present(level, shop, seller);
        if (player == null) { cancel(level, shop, y.getUUID(), "seller_left"); return null; }
        int price = order.price();
        UUID token = YukkuriWorldData.get(level).capture(y, () -> {
            y.pet().unlock(SELL);
            y.life().releaseHome();
            Custody.assignRaw(y, shop.id, Custody.OwnerKind.NPC, Custody.Role.STOCK.id());
            y.pet().rotateSerial();
        });
        if (token == null) {
            cancel(level, shop, y.getUUID(), "capture");
            return null;
        }
        staff.setItemInHand(InteractionHand.MAIN_HAND, CarrierContents.plain(YukkuriWorldData.get(level), token));
        ShopRegistry.get(level).endOrder(shop, y.getUUID());
        pay(player, price);
        SurvivalProgress.award(player, "pet_sold");
        say(player, "sell.paid", price, y.getDisplayName());
        GameplayLog.event(staff, "shop", "action=sell_live result=committed shop={} seller={} target={} token={} emeralds_received={}",
                shop.id, seller, y.getUUID(), token, price);
        return token;
    }

    // ---- loose baskets ----------------------------------------------------------------------------------------------------

    public static void pickedUp(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        UUID token = CarrierContents.singleToken(event.getOriginalStack());
        if (token == null) return;
        var level = player.serverLevel();
        UUID animal = YukkuriWorldData.get(level).captiveIdentity(token);
        if (animal == null) return;
        var registry = ShopRegistry.get(level);
        for (var shop : registry.shops()) {
            var order = shop.orders.get(animal);
            if (order != null && token.equals(order.token()) && player.getUUID().equals(order.customer()))
                registry.endOrder(shop, animal);
        }
    }

    /** A destroyed or lost paid basket can be reissued at the shop with the same animal and a fresh token. */
    static void recoverPaid(ServerLevel level, ShopRegistry.Entry shop) {
        var data = YukkuriWorldData.get(level);
        var registry = ShopRegistry.get(level);
        for (var entry : java.util.List.copyOf(shop.orders.entrySet())) {
            var order = entry.getValue();
            UUID token = order.token();
            if (token == null) continue;
            if (!data.hasToken(token)) { registry.endOrder(shop, entry.getKey()); continue; }
            var player = order.customer() == null ? null : level.getServer().getPlayerList().getPlayer(order.customer());
            if (player != null && player.getInventory().items.stream().anyMatch(s -> token.equals(CarrierContents.singleToken(s)))) {
                registry.endOrder(shop, entry.getKey());
                continue;
            }
            if (!ShopRestock.shopLoaded(level, shop) || ShopStaff.holdsBasket(level, token)) continue;
            if (order.customer() != null && present(level, shop, order.customer()) == null) continue;
            var at = Vec3.atBottomCenterOf(shop.counter());
            var basket = drop(level, order.customer(), at, CarrierContents.plain(data, token));
            if (basket == null) continue;
            var replacement = data.rotateToken(token);
            if (replacement == null) { basket.discard(); continue; }
            basket.setItem(CarrierContents.plain(data, replacement));
            registry.order(shop, entry.getKey(), new ShopRegistry.Order(order.kind(), order.customer(), order.price(), replacement));
            GameplayLog.world(level, "shop", "action=buy_live result=recovered shop={} token={} buyer={}", shop.id, replacement, order.customer());
        }
    }

    /** A basket set down on the floor; {@code target} alone may pick it up (a buyer, or the shop itself so no customer takes stock). */
    static ItemEntity drop(ServerLevel level, UUID target, Vec3 at, ItemStack basket) {
        var item = ModRandom.created(new ItemEntity(level, at.x, at.y + .15, at.z, basket), ModRandom.world(level));
        item.setTarget(target);
        item.setDefaultPickUpDelay();
        item.setUnlimitedLifetime();
        return level.addFreshEntity(item) ? item : null;
    }
}
