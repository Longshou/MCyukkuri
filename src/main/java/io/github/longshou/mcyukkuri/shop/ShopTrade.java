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
 * Orders, reservations and buyback at a pet shop (docs/FEATURES.md, 宠物店). An order is the animal's own session lock: {@link #SALE} held by
 * the buyer, {@link #HANDOVER} held by the shop for an operator handover, {@link #SELL} held by the seller on its own companion. While locked
 * the animal stands and waits; the first free staff member serves it ({@link ShopStaff}). Every refusal a player can meet at the click is
 * stated with its reason; once accepted, an order ends only in the basket (paid in that same tick) or in a cancellation that says why and
 * takes nothing.
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
    private static void tell(ServerLevel level, UUID player, String key, Object... args) {
        var p = player == null ? null : level.getServer().getPlayerList().getPlayer(player);
        if (p != null) p.sendSystemMessage(Component.translatable("shop.mcyukkuri." + key, args));
    }
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
        if (y.pet().busy()) return "ordered";
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
        y.playAction("joy_jump"); y.showReaction("happy", "shop_bought", 40); y.getLookControl().setLookAt(p, 30, 30);
        say(p, "buy.accepted", y.getDisplayName(), need);
        GameplayLog.event(y, "shop", "action=buy_live result=accepted shop={} buyer={} due={}", shop.id, p.getUUID(), need);
        return true;
    }

    /** Operator handover (command): the same fetch with no charge, the session held by a staff member of the shop (a lock holder must be present). */
    static boolean handover(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, HumanNpc holder) {
        if (y == null || !y.isAlive() || !shop.contains(y.position()) || !stockOf(y, shop.id) || !y.pet().lock(HANDOVER, holder.getUUID())) return false;
        GameplayLog.event(y, "shop", "action=buy_live result=accepted shop={} buyer=operator due=0", shop.id);
        return true;
    }

    /** What stops a waiting purchase from going on, or null. Checked every staff tick and every step before the basket. */
    static String orderProblem(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y) {
        if (!y.isAlive() || !shop.contains(y.position()) || !stockOf(y, shop.id)) return "gone";
        if (y.pet().busyWith().equals(HANDOVER)) return null;
        if (!level.isDay()) return "closed";
        return present(level, shop, y.pet().lockedBy()) == null ? "buyer_left" : null;
    }

    /** A purchase or sale that cannot go on: the animal is let go and the customer is told why; nothing was paid. */
    static void cancel(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, String reason) {
        String kind = y.pet().busyWith();
        UUID customer = y.pet().lockedBy();
        y.pet().unlock(kind);
        boolean sale = !kind.equals(SELL);
        if (!kind.equals(HANDOVER)) tell(level, customer, (sale ? "buy" : "sell") + ".cancelled." + reason, y.getDisplayName());
        GameplayLog.event(y, "shop", "action={} result=cancelled shop={} customer={} reason={} emeralds_used=0",
                sale ? "buy_live" : "sell_live", shop.id, customer, reason);
    }

    /**
     * The animal climbs into the staff member's basket: the buyer pays what is still due and owns it, in the same tick. A buyer who no
     * longer has the emeralds cancels instead. An operator handover is free and goes to {@code handoverTo}. Returns the basket token,
     * or null when the purchase did not happen.
     */
    static UUID captureSale(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, YukkuriEntity y, UUID handoverTo) {
        boolean operator = y.pet().busyWith().equals(HANDOVER);
        UUID buyer = operator ? handoverTo : y.pet().lockedBy();
        var player = operator ? null : present(level, shop, buyer);
        int due = operator ? 0 : due(shop, y);
        if (!operator && (player == null || emeralds(player) < due)) {
            cancel(level, shop, y, player == null ? "buyer_left" : "emeralds");
            return null;
        }
        var previous = HumanCommerce.data(y).copy();
        y.pet().unlock(y.pet().busyWith());
        y.life().releaseHome();
        var tag = previous.copy(); tag.remove(SHIP_OUT); HumanCommerce.set(y, tag);
        Custody.assign(y, buyer, Custody.OwnerKind.PLAYER, Custody.Role.COMPANION);
        UUID token = YukkuriWorldData.get(level).capture(y);
        if (token == null) {
            Custody.restore(y, previous);
            GameplayLog.event(y, "shop", "action=buy_live result=cancelled shop={} customer={} reason=capture_refused emeralds_used=0", shop.id, buyer);
            if (player != null) say(player, "buy.cancelled.capture", y.getDisplayName());
            return null;
        }
        staff.setItemInHand(InteractionHand.MAIN_HAND, CarrierContents.plain(YukkuriWorldData.get(level), token));
        ShopRegistry.get(level).sold(shop, y.getUUID());
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
    static void handOver(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, UUID buyer, ItemStack basket, UUID token) {
        var player = buyer == null ? null : level.getServer().getPlayerList().getPlayer(buyer);
        boolean hands = player != null && player.level() == level && player.isAlive() && player.distanceTo(staff) <= HAND_REACH && player.getInventory().add(basket.copy());
        if (!hands) drop(level, buyer, counterTop(level, staff), basket.copy());
        staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        staff.swing(InteractionHand.MAIN_HAND);
        if (player != null) say(player, hands ? "buy.delivered" : "buy.on_counter");
        GameplayLog.event(staff, "shop", "action=buy_live result=delivered shop={} buyer={} token={} placement={} handoff_at={}",
                shop.id, buyer == null ? "operator" : buyer, token, hands ? "inventory" : "counter", staff.blockPosition().toShortString());
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
        if (y.pet().busy() || y.isPassenger() || y.isVehicle()) return "busy";
        return ShopStaff.freeNest(level, shop, SpeciesProfile.of(y.getVariant()).dangerToYukkuri(), y.position()) == null ? "no_nest" : null;
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
        var pers = y.life().personality();
        y.getLookControl().setLookAt(p, 30, 30);
        // Its own numbers answer being sold: a fond one droops in tears, a proud one turns its face away, the rest bow.
        if (pers.attachment() >= 60 || pers.trust(p.getUUID()) >= BondLevels.TRUST_AT_20) { y.playAction("droop"); y.showReaction("cry", "shop_sold_sad", 80); }
        else if (pers.friendliness() <= 40) { y.playAction("ignore"); y.showReaction("smug", "shop_sold_proud", 80); }
        else y.playAction("bow");
        say(p, "sell.accepted", y.getDisplayName(), ShopPricing.buyback(y));
        GameplayLog.event(y, "shop", "action=sell_live result=accepted shop={} seller={} offer={}", shop.id, p.getUUID(), ShopPricing.buyback(y));
        return true;
    }

    /** What stops a waiting sale from going on, or null. */
    static String sellProblem(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y) {
        UUID seller = y.pet().lockedBy();
        if (!y.isAlive() || seller == null || !y.pet().ownedBy(seller) || !shop.contains(y.position())) return "gone";
        if (!level.isDay()) return "closed";
        return present(level, shop, seller) == null ? "seller_left" : null;
    }

    /** The sold companion climbs into the staff basket: it becomes the shop's stock and the seller is paid, in the same tick. */
    static UUID captureSold(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, YukkuriEntity y) {
        UUID seller = y.pet().lockedBy();
        var player = present(level, shop, seller);
        if (player == null) { cancel(level, shop, y, "seller_left"); return null; }
        int price = ShopPricing.buyback(y);
        var previous = HumanCommerce.data(y).copy();
        y.pet().unlock(SELL);
        y.life().releaseHome();
        Custody.assignRaw(y, shop.id, Custody.OwnerKind.NPC, Custody.Role.STOCK.id());
        UUID token = YukkuriWorldData.get(level).capture(y);
        if (token == null) {
            Custody.restore(y, previous);
            GameplayLog.event(y, "shop", "action=sell_live result=cancelled shop={} customer={} reason=capture_refused emeralds_used=0", shop.id, seller);
            say(player, "sell.cancelled.capture", y.getDisplayName());
            return null;
        }
        y.pet().rotateSerial(); // a badge token the seller still holds must not outlive the sale
        staff.setItemInHand(InteractionHand.MAIN_HAND, CarrierContents.plain(YukkuriWorldData.get(level), token));
        pay(player, price);
        SurvivalProgress.award(player, "pet_sold");
        say(player, "sell.paid", price, y.getDisplayName());
        GameplayLog.event(staff, "shop", "action=sell_live result=committed shop={} seller={} target={} token={} emeralds_received={}",
                shop.id, seller, y.getUUID(), token, price);
        return token;
    }

    // ---- loose baskets ----------------------------------------------------------------------------------------------------

    /** A basket set down on the floor; {@code target} alone may pick it up (a buyer, or the shop itself so no customer takes stock). */
    static ItemEntity drop(ServerLevel level, UUID target, Vec3 at, ItemStack basket) {
        var item = ModRandom.created(new ItemEntity(level, at.x, at.y + .15, at.z, basket), ModRandom.world(level));
        item.setTarget(target);
        item.setDefaultPickUpDelay();
        item.setUnlimitedLifetime();
        return level.addFreshEntity(item) ? item : null;
    }
}
