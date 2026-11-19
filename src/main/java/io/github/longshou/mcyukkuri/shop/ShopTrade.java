package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.ProduceCrateBlockEntity;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * Accepted orders live in the shop register; the animal's lock keeps it in place while staff fetch it.
 * A paid purchase remains there until the customer takes the basket.
 */
public final class ShopTrade {
    public static final String SALE = "sale", HANDOVER = "handover", SELL = "sell";
    /** A buyer within this range and in sight can take the basket directly from the staff member. */
    static final double HAND_REACH = 4.5;
    /** A buyer or seller counts as in the shop within this margin around the footprint (the pavement in front of the door included). */
    private static final double PRESENT_MARGIN = 4;
    static final String SHIP_OUT = "ShipOut", FOR_SALE_SINCE = "ForSaleSince", SHIP_AFTER = "ShipAfter";
    public static final int RESTRAINT_DEPOSIT = 4;
    private static final String RESTRAINT_DUE_DAY = "RestraintDueDay";
    private ShopTrade() {}

    public static boolean stockOf(YukkuriEntity y, UUID keeper) { return HumanCommerce.ownedBy(y, keeper) && Custody.view(y).role() == Custody.Role.STOCK; }
    public static ShopRegistry.Entry shopOfStock(YukkuriEntity y) {
        if (!(y.level() instanceof ServerLevel level) || Custody.view(y).role() != Custody.Role.STOCK) return null;
        var shop = ShopRegistry.get(level).shop(Custody.view(y).owner());
        return shop != null && shop.dimension.equals(level.dimension().location().toString()) && shop.contains(y.position()) ? shop : null;
    }

    public static long cardActions(Player viewer, YukkuriEntity animal) {
        var shop = shopOfStock(animal);
        if (shop == null) return 0;
        long mask = (1L << CareAction.SHOP_BUY.id()) | (1L << CareAction.SHOP_ABOUT.id());
        var deposit = shop.deposit(animal.getUUID());
        if (deposit == null) {
            if (!shop.closed()) mask |= 1L << CareAction.SHOP_RESERVE.id();
        } else if (deposit.buyer().equals(viewer.getUUID())) {
            mask |= 1L << CareAction.SHOP_CANCEL.id();
        }
        return mask;
    }

    public static boolean card(ServerPlayer player, YukkuriEntity animal, CareAction action) {
        var level = player.serverLevel();
        var shop = shopOfStock(animal);
        if (action == CareAction.SHOP_CANCEL) {
            var id = animal.getUUID();
            var deposit = shop.deposit(id);
            if (deposit == null || !deposit.buyer().equals(player.getUUID())) return false;
            var order = shop.orders.get(id);
            if (order != null && order.kind().equals(SALE)) cancel(level, shop, id, "reservation");
            ShopRestock.lapse(level, shop, id, animal, true);
            return true;
        }
        if (action == CareAction.SHOP_ABOUT) return ShopInfo.send(player, shop, animal);
        if (ShopAccess.refuseService(level, shop, player)) return false;
        ShopRestock.reconcileReservations(level, shop);
        return switch (action) {
            case SHOP_BUY -> buy(level, shop, animal, player);
            case SHOP_RESERVE -> reserve(level, shop, animal, player);
            default -> throw new IllegalArgumentException(action.name());
        };
    }
    static boolean order(YukkuriEntity y) { return y.pet().busyWith().equals(SALE) || y.pet().busyWith().equals(HANDOVER); }
    /** The price still due from this buyer: the reserved price less the deposit for its reserver, else today's quote. */
    static int due(ShopRegistry.Entry shop, YukkuriEntity y) {
        var d = shop.deposit(y.getUUID());
        return d == null ? ShopPricing.price(shop.kind, y) : d.price() - d.paid();
    }
    /** A quarter of the price, at least one emerald. */
    static int deposit(int price) { return Math.max(1, (price + 3) / 4); }

    // ---- emeralds -----------------------------------------------------------------------------------------------------

    static int emeralds(Player p) { return p.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum(); }
    static void debit(Player p, int amount) {
        for (ItemStack stack : p.getInventory().items) if (stack.is(Items.EMERALD)) { int used = Math.min(amount, stack.getCount()); stack.shrink(used); amount -= used; if (amount == 0) return; }
    }
    static int pay(Player p, int amount, Item currency) {
        int unpaid = 0;
        for (; amount > 0; amount -= 64) {
            var stack = new ItemStack(currency, Math.min(64, amount));
            p.getInventory().add(stack);
            if (!stack.isEmpty() && p.drop(stack, false) == null) unpaid += stack.getCount();
        }
        return unpaid;
    }
    static void say(Player p, String key, Object... args) { p.displayClientMessage(Component.translatable("shop.mcyukkuri." + key, args), true); }

    static boolean lendRestraint(ServerPlayer player, HumanNpc staff, ProduceCrateBlockEntity tray) {
        if (emeralds(player) < RESTRAINT_DEPOSIT) return restraintRefused(player, staff, "emeralds", RESTRAINT_DEPOSIT);
        var inventory = player.getInventory();
        int slot = inventory.getFreeSlot();
        if (slot < 0) return restraintRefused(player, staff, "inventory", RESTRAINT_DEPOSIT);
        var table = tray.getItem(0);
        debit(player, RESTRAINT_DEPOSIT);
        tray.getPersistentData().putInt(RESTRAINT_DUE_DAY, ShopStock.today(player.serverLevel()) + 1);
        tray.setItem(0, new ItemStack(Items.EMERALD, RESTRAINT_DEPOSIT));
        inventory.setItem(slot, table);
        restraintHandOver(player, staff);
        say(player, "restraint.lent", RESTRAINT_DEPOSIT);
        GameplayLog.event(player, "shop", "action=lend_restraint result=committed via=menu staff={} tray={} deposit={}",
                staff.getUUID(), tray.getBlockPos(), RESTRAINT_DEPOSIT);
        return true;
    }

    static boolean returnRestraint(ServerPlayer player, HumanNpc staff, ProduceCrateBlockEntity tray) {
        var inventory = player.getInventory();
        var table = ModItems.RESTRAINT.get();
        int source = inventory.selected;
        if (!inventory.getItem(source).is(table)) {
            for (source = 0; source < inventory.getContainerSize() && !inventory.getItem(source).is(table); source++) {}
            if (source == inventory.getContainerSize()) return restraintRefused(player, staff, "return_frame", tray.getItem(0).getCount());
        }
        var returned = inventory.getItem(source);
        var deposit = tray.getItem(0);
        int price = deposit.getCount();
        if (returned.getCount() == 1) {
            inventory.setItem(source, deposit);
        } else {
            int slot = inventory.getSlotWithRemainingSpace(deposit);
            if (slot < 0 || inventory.getItem(slot).getCount() + price > deposit.getMaxStackSize()) slot = inventory.getFreeSlot();
            if (slot < 0 && drop(player.serverLevel(), player.getUUID(), player.position(), deposit.copy()) == null)
                return restraintRefused(player, staff, "inventory", price);
            if (slot >= 0) inventory.add(slot, deposit.copy());
        }
        tray.getPersistentData().remove(RESTRAINT_DUE_DAY);
        tray.setItem(0, returned.split(1));
        restraintHandOver(player, staff);
        say(player, "restraint.returned", price);
        GameplayLog.event(player, "shop", "action=return_restraint result=committed via=menu staff={} tray={} deposit={}",
                staff.getUUID(), tray.getBlockPos(), price);
        return true;
    }

    private static void restraintHandOver(ServerPlayer player, HumanNpc staff) {
        staff.getLookControl().setLookAt(player);
        staff.swing(InteractionHand.MAIN_HAND);
    }

    static boolean restraintRefused(ServerPlayer player, HumanNpc staff, String reason, int price) {
        say(player, "restraint." + reason, price);
        GameplayLog.limited(player, "shop", "restraint." + reason, 100,
                "action=rent_restraint result=refused via=menu staff={} reason={} deposit={}", staff.getUUID(), reason, price);
        return false;
    }

    /** The tray holds the deposit and its deadline together, including when the counter chunk unloads. */
    static void expireRestraint(ServerLevel level, ShopRegistry.Entry shop, ProduceCrateBlockEntity tray) {
        var deposit = tray.getItem(0);
        if (!deposit.is(Items.EMERALD)) return;
        var data = tray.getPersistentData();
        int today = ShopStock.today(level);
        if (!data.contains(RESTRAINT_DUE_DAY)) {
            // A deposit saved before rentals had deadlines can be recovered until the next shop day.
            data.putInt(RESTRAINT_DUE_DAY, today + 1);
            tray.setChanged();
            return;
        }
        if (today < data.getInt(RESTRAINT_DUE_DAY)) return;
        ShopRegistry.get(level).depositInTill(shop, deposit.getCount());
        data.remove(RESTRAINT_DUE_DAY);
        tray.setItem(0, ItemStack.EMPTY);
        GameplayLog.world(level, "shop", "action=rent_restraint result=expired shop={} tray={} deposit={} till={}",
                shop.id, tray.getBlockPos(), deposit.getCount(), shop.emeraldTill);
    }

    static void restockRestraint(ServerLevel level, ShopRegistry.Entry shop, ProduceCrateBlockEntity tray) {
        expireRestraint(level, shop, tray);
        if (!tray.getItem(0).isEmpty()) return;
        tray.setItem(0, new ItemStack(ModItems.RESTRAINT.get()));
        GameplayLog.world(level, "shop", "action=rent_restraint result=restocked shop={} tray={}", shop.id, tray.getBlockPos());
    }

    static ServerPlayer present(ServerLevel level, ShopRegistry.Entry shop, UUID id) {
        var p = id == null ? null : level.getServer().getPlayerList().getPlayer(id);
        return p != null && p.level() == level && p.isAlive() && !p.isSpectator()
                && shop.bounds().inflate(PRESENT_MARGIN, 2, PRESENT_MARGIN).contains(p.position()) ? p : null;
    }

    // ---- buying ---------------------------------------------------------------------------------------------------------

    /** Why this player cannot order or reserve this animal right now, or null. These are the only refusals at the click. */
    static String buyRefusal(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, Player p, int need) {
        if (shop.closed() || !level.isDay()) return "closed";
        if (y == null || !y.isAlive() || !shop.contains(y.position()) || !stockOf(y, shop.id)) return "gone";
        var d = shop.deposit(y.getUUID());
        if (d != null && !d.buyer().equals(p.getUUID())) return "reserved";
        if (!shop.membership(p.getUUID()).allows(ShopMembership.required(y))) return "tier";
        if (y.pet().busy() || shop.orders.containsKey(y.getUUID())) return "ordered";
        return emeralds(p) < need ? "emeralds" : null;
    }

    private static boolean refuseTier(ServerPlayer player, ShopRegistry.Entry shop, YukkuriEntity animal,
                                      String action, String verb) {
        var required = ShopMembership.required(animal);
        var id = player.getUUID();
        int missing = required.threshold(shop.kind) - shop.spend(id);
        say(player, verb + ".refused.tier", required.nameText(), missing);
        GameplayLog.event(player, "shop", "action={} result=refused shop={} target={} reason=tier required={} tier={} missing={}",
                action, shop.id, animal.getUUID(), required, shop.membership(id), missing);
        return false;
    }

    /** The player orders this stock animal: it waits for the first free staff member to bring it to the counter. */
    static boolean buy(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, ServerPlayer p) {
        int need = y == null ? 0 : due(shop, y);
        String why = buyRefusal(level, shop, y, p, need);
        if (why != null) {
            if (why.equals("tier")) return refuseTier(p, shop, y, "buy_live", "buy");
            say(p, "buy.refused." + why, need, emeralds(p));
            GameplayLog.event(p, "shop", "action=buy_live result=refused shop={} target={} reason={} due={} emeralds={}",
                    shop.id, y == null ? null : y.getUUID(), why, need, emeralds(p));
            return false;
        }
        y.pet().lock(SALE, p.getUUID());
        ShopRegistry.get(level).order(shop, y.getUUID(), new ShopRegistry.Order(SALE, p.getUUID(), need, null));
        chosenReaction(y, p, shop, "buy_live", "accepted", need, "shop_bought");
        say(p, "buy.accepted", y.getDisplayName(), need);
        return true;
    }

    private static void chosenReaction(YukkuriEntity y, ServerPlayer p, ShopRegistry.Entry shop,
                                       String action, String result, int due, String line) {
        var personality = y.life().personality();
        var bond = personality.relation(p.getUUID());
        double esteem = SocialDisposition.selfEsteem(y);
        double wary = SocialDisposition.humanWariness(y, p, bond);
        boolean guarded = wary >= .4;
        boolean joyful = !guarded && (SocialDisposition.confidence(esteem) >= .55 || personality.temperament().is(Gate.DEVOTED)
                || bond.closeness() >= BondLevels.TRUST_FAMILIAR);
        y.playAction(joyful ? "joy_jump" : "bow");
        y.showReaction(guarded ? "flushed_smile" : "happy", guarded ? "hello" : line, 40);
        y.getLookControl().setLookAt(p, 30, 30);
        GameplayLog.event(y, "shop", "action={} result={} shop={} buyer={} due={} reaction={} esteem={} wariness={} closeness={} fear={}",
                action, result, shop.id, p.getUUID(), due, guarded ? "guarded_bow" : joyful ? "joyful" : "bow", esteem, wary, bond.closeness(), bond.fear());
    }

    /** Operator handover uses the same saved fetch and charges no emeralds. */
    static boolean handover(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, UUID buyer) {
        if (y == null || !y.isAlive() || !shop.contains(y.position()) || !stockOf(y, shop.id) || shop.orders.containsKey(y.getUUID())
                || shop.deposit(y.getUUID()) != null) return false;
        boolean sleeping = y.life().isSleeping();
        if (!y.pet().lock(HANDOVER, null)) return false;
        ShopRegistry.get(level).order(shop, y.getUUID(), new ShopRegistry.Order(HANDOVER, buyer, 0, null));
        GameplayLog.event(y, "shop", "action=buy_live result=accepted shop={} buyer=operator due=0 sleeping={}", shop.id, sleeping);
        return true;
    }

    /** What stops a waiting purchase from going on, or null. Checked every staff tick and every step before the basket. */
    static String orderProblem(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, ShopRegistry.Order order) {
        if (order.kind().equals(SELL)) return sellProblem(level, shop, y, order.customer());
        if (!y.isAlive() || !shop.contains(y.position()) || !stockOf(y, shop.id)) return "gone";
        if (order.kind().equals(HANDOVER)) return null;
        if (!level.isDay()) return "closed";
        var buyer = present(level, shop, order.customer());
        if (buyer == null) return "buyer_left";
        return ShopAccess.service(level, shop, buyer) == ShopAccess.Service.AVAILABLE ? null : "service";
    }

    /** A purchase or sale that cannot go on: the animal is let go and the customer is told why; nothing was paid. */
    static void cancel(ServerLevel level, ShopRegistry.Entry shop, UUID animal, String reason) {
        var order = ShopRegistry.get(level).endOrder(shop, animal);
        if (order == null) return;
        ShopStaff.finishFetch(animal);
        String kind = order.kind();
        UUID customer = order.customer();
        var y = level.getEntity(animal) instanceof YukkuriEntity target ? target : null;
        if (y != null) y.pet().unlock(kind);
        boolean sale = !kind.equals(SELL);
        if (reason.equals("service")) ShopAccess.refuseService(level, shop, (ServerPlayer) level.getPlayerByUUID(customer));
        else if (!kind.equals(HANDOVER)) ShopRegistry.get(level).tell(level, customer, (sale ? "buy" : "sell") + ".cancelled." + reason,
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
            var tag = HumanCommerce.data(y).copy(); tag.remove(SHIP_OUT); tag.remove(SHIP_AFTER); HumanCommerce.set(y, tag);
            Custody.assign(y, buyer, Custody.OwnerKind.PLAYER, Custody.Role.COMPANION);
        });
        if (token == null) {
            cancel(level, shop, y.getUUID(), "capture");
            return null;
        }
        ShopStaff.fillCarrier(level, staff, token);
        ShopStaff.finishFetch(y.getUUID());
        var registry = ShopRegistry.get(level);
        registry.order(shop, y.getUUID(), new ShopRegistry.Order(order.kind(), buyer, due, token));
        var deposit = registry.endDeposit(level, shop, y.getUUID());
        if (player != null) {
            debit(player, due);
            registry.recordSpend(level, shop, buyer, due + (deposit == null ? 0 : deposit.paid()));
            SurvivalProgress.award(player, "pet_bought");
            say(player, "buy.paid", due, y.getDisplayName());
        }
        GameplayLog.event(staff, "shop", "action=buy_live result=committed shop={} buyer={} target={} token={} emeralds_used={}",
                shop.id, buyer == null ? "operator" : buyer, y.getUUID(), token, due);
        return token;
    }

    /** Place the same carrier in the buyer's hands, on the counter within reach, or at the staff member's feet. */
    static boolean handOver(ServerLevel level, HumanNpc staff, ShopRegistry.Entry shop, ServerPlayer player, UUID buyer,
                            ItemStack basket, UUID token, boolean atCounter) {
        boolean hands = player != null && player.distanceToSqr(staff) <= HAND_REACH * HAND_REACH
                && staff.hasLineOfSight(player) && player.getInventory().add(basket.copy());
        if (!hands && drop(level, buyer, atCounter ? counterTop(level, shop) : staff.position(), basket.copy()) == null) return false;
        staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        if (hands || buyer == null) ShopRegistry.get(level).endOrder(shop, YukkuriWorldData.get(level).captiveIdentity(token));
        staff.swing(InteractionHand.MAIN_HAND);
        ShopRegistry.get(level).tell(level, buyer, hands ? "buy.delivered" : atCounter ? "buy.on_counter" : "buy.on_floor");
        GameplayLog.event(staff, "shop", "action=buy_live result=delivered shop={} buyer={} token={} placement={} handoff_at={}",
                shop.id, buyer == null ? "operator" : buyer, token, hands ? "inventory" : atCounter ? "counter" : "floor", staff.blockPosition().toShortString());
        return true;
    }

    /** The top of the counter block beside the staff member (the counter faces the customer side), else its own feet. */
    private static Vec3 counterTop(ServerLevel level, ShopRegistry.Entry shop) {
        var at = shop.counter();
        var cell = ShopRegistry.authoredCounterTop(shop);
        if (cell != null) {
            var state = level.getBlockState(cell);
            if (state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM)
                return Vec3.atBottomCenterOf(cell).add(0, .5, 0);
            if (shop.kind == ShopKind.ONI && state.isAir()) return Vec3.atBottomCenterOf(cell);
        }
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var c = at.relative(d);
            if (level.getBlockState(c).isSolid() && level.getBlockState(c.above()).isAir()) return Vec3.atBottomCenterOf(c.above());
        }
        return Vec3.atBottomCenterOf(at);
    }

    // ---- reservations ---------------------------------------------------------------------------------------------------

    /** The player pays a deposit to keep this animal: no one else may buy it, and the three-day restock never ships it out. */
    static boolean reserve(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, ServerPlayer p) {
        int price = y == null ? 0 : ShopPricing.price(shop.kind, y), paid = deposit(price);
        String why = y != null && shop.deposit(y.getUUID()) != null && shop.deposit(y.getUUID()).buyer().equals(p.getUUID()) ? "own"
                : buyRefusal(level, shop, y, p, paid);
        if (why != null) {
            if (why.equals("tier")) return refuseTier(p, shop, y, "reserve", "reserve");
            say(p, "reserve.refused." + why, paid, emeralds(p));
            GameplayLog.event(p, "shop", "action=reserve result=refused shop={} target={} reason={}", shop.id, y == null ? null : y.getUUID(), why);
            return false;
        }
        debit(p, paid);
        ShopRegistry.get(level).reserve(shop, y.getUUID(), p.getUUID(), price, paid, level.getGameTime());
        y.showReservation(p.getUUID());
        var tag = HumanCommerce.data(y).copy(); tag.remove(SHIP_OUT); HumanCommerce.set(y, tag);
        chosenReaction(y, p, shop, "reserve_reaction", "shown", price - paid, "shop_reserved");
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
        if (!shop.kind.accepts(y)) return "oni_predator";
        if (!shop.contains(y.position())) return "outside";
        if (y.pet().busy() || shop.orders.containsKey(y.getUUID()) || y.isPassenger() || y.isVehicle()) return "busy";
        return !shop.legacy() && ShopStaff.freePlace(level, shop, SpeciesProfile.of(y.getVariant()).dangerToYukkuri(), y.position()) == null ? "no_nest" : null;
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
        int price = ShopPricing.buyback(shop.kind, y);
        ShopRegistry.get(level).order(shop, y.getUUID(), new ShopRegistry.Order(SELL, p.getUUID(), price, null));
        var personality = y.life().personality();
        var bond = personality.relation(p.getUUID());
        double esteem = SocialDisposition.selfEsteem(y);
        double wary = SocialDisposition.humanWariness(y, p, bond);
        double fondness = SocialDisposition.ramp(personality.attachment(), 45, 80)
                * SocialDisposition.ramp(bond.closeness(), 0, 20);
        double composure = SocialDisposition.confidence(esteem) * (1 - wary)
                * (1 - SocialDisposition.ramp(personality.friendliness(), 35, 75));
        y.getLookControl().setLookAt(p, 30, 30);
        boolean sad = fondness >= .4, aloof = !sad && composure >= .55;
        if (sad) { y.playAction("droop"); y.showReaction("cry", "shop_sold_sad", 80); }
        else if (aloof) { y.playAction("ignore"); y.showReaction("smug", "shop_sold_proud", 80); }
        else { y.playAction("bow"); y.showReaction(wary >= .4 ? "flushed_smile" : "closed_smile", "", 40); }
        say(p, shop.kind == ShopKind.ONI ? "sell.accepted_oni" : "sell.accepted", y.getDisplayName(), price);
        GameplayLog.event(y, "shop", "action=sell_live result=accepted shop={} seller={} offer={} reaction={} esteem={} wariness={} closeness={} fear={}",
                shop.id, p.getUUID(), price, sad ? "sad" : aloof ? "aloof" : "bow", esteem, wary, bond.closeness(), bond.fear());
        return true;
    }

    /** What stops a waiting sale from going on, or null. */
    private static String sellProblem(ServerLevel level, ShopRegistry.Entry shop, YukkuriEntity y, UUID seller) {
        if (!y.isAlive() || seller == null || !y.pet().ownedBy(seller) || !shop.contains(y.position())) return "gone";
        if (!level.isDay()) return "closed";
        var player = present(level, shop, seller);
        if (player == null) return "seller_left";
        return ShopAccess.service(level, shop, player) == ShopAccess.Service.AVAILABLE ? null : "service";
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
            shop.kind.prepareStock(y);
            y.pet().rotateSerial();
        });
        if (token == null) {
            cancel(level, shop, y.getUUID(), "capture");
            return null;
        }
        ShopStaff.fillCarrier(level, staff, token);
        ShopRegistry.get(level).endOrder(shop, y.getUUID());
        int unpaid = pay(player, price, shop.kind.buybackCurrency());
        if (unpaid > 0) ShopRegistry.get(level).owe(shop, seller, unpaid, false);
        SurvivalProgress.award(player, "pet_sold");
        var receipt = unpaid == 0 ? "sell.paid" : "sell.owed";
        say(player, shop.kind == ShopKind.ONI ? receipt + "_oni" : receipt, price - unpaid, y.getDisplayName(), unpaid);
        GameplayLog.event(staff, "shop", "action=sell_live result=committed shop={} seller={} target={} token={} {}_received={}",
                shop.id, seller, y.getUUID(), token, shop.kind == ShopKind.ONI ? "gold_nuggets" : "emeralds", price - unpaid);
        return token;
    }

    // ---- loose baskets ----------------------------------------------------------------------------------------------------

    public static void pickingUp(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        var item = event.getItemEntity();
        var target = item.getTarget();
        if (target == null) return;
        var level = player.serverLevel();
        var shop = ShopRegistry.get(level).shop(target);
        if (shop == null) return; // A paid basket still follows its buyer's vanilla pickup target.
        if (shop.closed() && !ShopStaff.shopBasket(level, shop, item.getItem())) item.setTarget(null);
        else event.setCanPickup(TriState.FALSE);
    }

    public static void pickedUp(ItemEntityPickupEvent.Post event) {
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
            if (order.kind().equals(SALE) && present(level, shop, order.customer()) == null) continue;
            var at = counterTop(level, shop);
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
    public static ItemEntity drop(ServerLevel level, UUID target, Vec3 at, ItemStack basket) {
        var item = ModRandom.created(new ItemEntity(level, at.x, at.y + .15, at.z, basket), ModRandom.world(level));
        item.setTarget(target);
        item.setDefaultPickUpDelay();
        item.setUnlimitedLifetime();
        if (!level.addFreshEntity(item)) return null;
        GameplayLog.event(item, "shop", "action=drop item={} count={} drop={} pickup={}",
                BuiltInRegistries.ITEM.getKey(basket.getItem()), basket.getCount(), item.getUUID(), target);
        return item;
    }
}
