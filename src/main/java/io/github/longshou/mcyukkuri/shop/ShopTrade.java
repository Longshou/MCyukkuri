package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** The menu and the operator handover share the same fetch, capture and custody transfer. */
public final class ShopTrade {
    private ShopTrade() {}

    /** Zero is the operator's uncharged handover. A menu quote is charged only when the basket is loaded. */
    public static boolean begin(ServerLevel level, HumanNpc clerk, YukkuriEntity animal, UUID buyer, int price) {
        var registry = ShopRegistry.get(level);
        var entry = registry.of(clerk);
        if (entry == null || !clerk.isAlive()) return false;
        UUID shop = entry.keeper, id = animal.getUUID();
        var player = level.getServer().getPlayerList().getPlayer(buyer);
        var deposit = entry.deposits.get(id);
        String reason = !animal.isAlive() || !ShopMenu.stockOf(animal, shop) || !entry.bounds().contains(animal.position()) ? "target_unavailable"
                : price > 0 && !level.isDay() ? "night"
                : deposit != null && !deposit.buyer().equals(buyer) ? "reserved"
                : price > 0 && (player == null || ShopMenu.count(player, Items.EMERALD) < remaining(entry, id, price)) ? "insufficient_emeralds"
                : animal.isPassenger() || animal.isVehicle() || animal.pet().busy() ? "busy" : null;
        if (reason != null || !animal.pet().lock("sale", holder(shop, buyer, price)))
            return refuse(clerk, player, id, reason == null ? "busy" : reason);
        GameplayLog.event(clerk, "shop", "action=buy_live result=accepted buyer={} target={} price={}", buyer, id, price);
        BlockPos counter = entry.pos;
        ShopPerformance.schedule(level, Set.of(shop, id), age -> {
            String reason = unavailable(level, shop, animal, buyer, price, counter);
            if (reason != null) {
                cancel(level, shop, animal, buyer, price, reason);
                return true;
            }
            return age % 20 == 0 && ShopCourier.dispatchPurchase(level, entry, animal, buyer, price, counter);
        }, () -> unlock(animal, shop, buyer, price));
        if (price > 0 && player != null) player.displayClientMessage(Component.translatable("shop.mcyukkuri.purchase_pending"), true);
        return true;
    }

    private static boolean refuse(HumanNpc clerk, ServerPlayer player, UUID animal, String reason) {
        GameplayLog.event(clerk, "shop", "action=buy_live result=refused target={} reason={}", animal, reason);
        if (player != null) player.displayClientMessage(Component.translatable("shop.mcyukkuri.refusal." + reason), true);
        return false;
    }

    static int remaining(ShopRegistry.Entry shop, UUID animal, int price) {
        var deposit = shop.deposits.get(animal);
        return deposit == null ? price : deposit.price() - deposit.paid();
    }

    private static UUID holder(UUID shop, UUID buyer, int price) { return price == 0 ? shop : buyer; }

    private static boolean ownsHold(YukkuriEntity animal, UUID shop, UUID buyer, int price) {
        return animal.pet().busyWith().equals("sale") && holder(shop, buyer, price).equals(animal.pet().lockedBy());
    }

    static void unlock(YukkuriEntity animal, UUID shop, UUID buyer, int price) {
        if (ownsHold(animal, shop, buyer, price)) animal.pet().unlock("sale");
    }

    private static ServerPlayer nearbyBuyer(ServerLevel level, UUID buyer, ShopRegistry.Entry shop) {
        var player = level.getServer().getPlayerList().getPlayer(buyer);
        return player != null && player.level() == level && player.isAlive() && !player.isSpectator()
                && shop != null && shop.bounds().inflate(4, 2, 4).contains(player.position()) ? player : null;
    }

    static String unavailable(ServerLevel level, UUID shop, YukkuriEntity animal, UUID buyer, int price, BlockPos counter) {
        var entry = ShopRegistry.get(level).shop(shop);
        if (entry == null) return "staff_unavailable";
        if (price > 0 && !level.isDay()) return "night";
        if (animal == null || level.getEntity(animal.getUUID()) != animal || !animal.isAlive()) return "target_unavailable";
        if (animal.isPassenger() || animal.isVehicle() || !ShopMenu.stockOf(animal, shop)
                || !entry.bounds().contains(animal.position())) return "stock_moved";
        if (!ownsHold(animal, shop, buyer, price)) return "sale_ended";
        return price > 0 && nearbyBuyer(level, buyer, entry) == null ? "buyer_left" : null;
    }

    static void cancel(ServerLevel level, UUID shop, YukkuriEntity animal, UUID buyer, int price, String reason) {
        if (animal != null) unlock(animal, shop, buyer, price);
        var player = level.getServer().getPlayerList().getPlayer(buyer);
        if (price > 0 && player != null) player.sendSystemMessage(Component.translatable("shop.mcyukkuri.refusal." + reason));
        GameplayLog.world(level, "shop", "action=buy_live result=cancelled shop={} buyer={} target={} reason={} emeralds_used=0",
                shop, buyer, animal == null ? null : animal.getUUID(), reason);
    }

    /** Validation, capture, payment and the keeper's loaded hand all happen on the same server tick. */
    static UUID capture(ServerLevel level, HumanNpc staff, UUID shop, YukkuriEntity animal, UUID buyer, int price, BlockPos counter) {
        var registry = ShopRegistry.get(level);
        var entry = registry.shop(shop);
        var player = price == 0 ? null : nearbyBuyer(level, buyer, entry);
        int due = entry == null ? price : remaining(entry, animal.getUUID(), price);
        if (price > 0 && (player == null || ShopMenu.count(player, Items.EMERALD) < due)) {
            cancel(level, shop, animal, buyer, price, player == null ? "buyer_left" : "insufficient_emeralds");
            return null;
        }
        unlock(animal, shop, buyer, price);
        var previous = HumanCommerce.data(animal).copy();
        Custody.assign(animal, buyer, Custody.OwnerKind.PLAYER, Custody.Role.COMPANION);
        var world = YukkuriWorldData.get(level);
        UUID token = world.capture(animal);
        if (token == null) {
            Custody.restore(animal, previous);
            cancel(level, shop, animal, buyer, price, "capture_failed");
            return null;
        }
        CarrierContents.add(staff.getMainHandItem(), token, world.captiveSnapshot(token));
        if (player != null) {
            ShopMenu.debit(player, Items.EMERALD, due);
            SurvivalProgress.award(player, "pet_bought");
            player.displayClientMessage(Component.translatable("shop.mcyukkuri.purchase_paid"), true);
        }
        if (entry != null) registry.complete(entry, animal.getUUID());
        GameplayLog.event(staff, "shop", "action=buy_live result=committed shop={} buyer={} target={} token={} emeralds_used={}",
                shop, buyer, animal.getUUID(), token, due);
        return token;
    }

    static boolean delivered(ServerLevel level, HumanNpc staff, UUID buyer, UUID id, UUID token, int price, ItemStack basket, BlockPos counter) {
        boolean atCounter = staff.blockPosition().equals(counter) && staff.onGround();
        var player = atCounter ? nearbyBuyer(level, buyer, ShopRegistry.get(level).of(staff)) : null;
        boolean received = player != null && player.getInventory().add(basket);
        if (!received && drop(level, buyer, staff.position(), basket) == null) return false;
        GameplayLog.event(staff, "shop", "action=buy_live result=delivered buyer={} target={} token={} price={} mode={} placement={} counter={} handoff_at={}",
                buyer, id, token, price, atCounter ? "keeper" : "fallback", received ? "inventory" : "ground",
                counter.toShortString(), staff.blockPosition().toShortString());
        return true;
    }

    static boolean canSell(ServerLevel level, UUID shop, YukkuriEntity animal, UUID seller) {
        var entry = ShopRegistry.get(level).shop(shop);
        var player = nearbyBuyer(level, seller, entry);
        return level.isDay() && player != null && animal != null && animal.isAlive() && !animal.isPassenger()
                && !animal.isVehicle() && animal.pet().ownedBy(seller) && animal.pet().busyWith().equals("sale")
                && seller.equals(animal.pet().lockedBy()) && animal.distanceToSqr(player) <= 144;
    }

    static UUID captureBuyback(ServerLevel level, HumanNpc staff, UUID shop, YukkuriEntity animal, UUID seller, int price, BlockPos pen) {
        if (!canSell(level, shop, animal, seller) || !(level.getEntity(shop) instanceof HumanNpc owner) || !owner.isAlive()) return null;
        var player = level.getServer().getPlayerList().getPlayer(seller);
        var previous = HumanCommerce.data(animal).copy();
        animal.pet().unlock("sale");
        Custody.assign(animal, owner, Custody.Role.STOCK);
        UUID token = YukkuriWorldData.get(level).capture(animal);
        if (token == null) {
            Custody.restore(animal, previous);
            return null;
        }
        staff.setItemInHand(InteractionHand.MAIN_HAND, HumanCommerce.carrier(animal, token));
        ShopMenu.giveEmeralds(player, price);
        SurvivalProgress.award(player, "pet_sold");
        player.displayClientMessage(Component.translatable("shop.mcyukkuri.buyback_paid", price), true);
        ShopRegistry.get(level).stocked(owner, animal);
        GameplayLog.event(player, "shop", "action=sell_live result=committed shop={} target={} token={} emeralds_received={} pen={}",
                shop, animal.getUUID(), token, price, pen.toShortString());
        return token;
    }

    static void buybackCancelled(ServerLevel level, UUID seller) {
        var player = seller == null ? null : level.getServer().getPlayerList().getPlayer(seller);
        if (player != null) player.sendSystemMessage(Component.translatable("shop.mcyukkuri.buyback_cancelled"));
    }

    static ItemEntity drop(ServerLevel level, UUID buyer, Vec3 at, ItemStack basket) {
        var item = ModRandom.created(new ItemEntity(level, at.x, at.y + .15, at.z, basket), ModRandom.world(level));
        item.setTarget(buyer);
        item.setDefaultPickUpDelay();
        item.setUnlimitedLifetime();
        return level.addFreshEntity(item) ? item : null;
    }
}
