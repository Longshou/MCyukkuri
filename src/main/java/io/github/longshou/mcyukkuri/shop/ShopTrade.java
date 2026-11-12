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
    private static final int JOY = 30, KEEPER_WAIT = 400;
    private static final double COUNTER_RANGE_SQR = 36;

    private ShopTrade() {}

    /** Zero is the operator's uncharged handover. A menu quote is charged only when the basket is loaded. */
    public static boolean begin(ServerLevel level, HumanNpc clerk, YukkuriEntity animal, UUID buyer, int price) {
        UUID shop = clerk.getUUID(), id = animal.getUUID();
        if (!clerk.isAlive() || clerk.job() != HumanNpc.Job.SHOPKEEPER || !animal.isAlive() || animal.isPassenger()
                || animal.isVehicle() || !ShopMenu.stockOf(animal, shop) || clerk.distanceToSqr(animal) > 48 * 48
                || !animal.pet().lock("sale", holder(shop, buyer, price))) {
            GameplayLog.event(clerk, "shop", "action=buy_live result=refused buyer={} target={} reason=stock_or_staff_unavailable", buyer, id);
            return false;
        }
        GameplayLog.event(clerk, "shop", "action=buy_live result=accepted buyer={} target={} price={}", buyer, id, price);
        animal.playAction("joy_jump");
        animal.showReaction("happy", "shop_bought", 40);
        BlockPos counter = ShopRegistry.get(level).register(clerk).pos;
        ShopPerformance.schedule(level, Set.of(shop, id), age -> {
            String reason = unavailable(level, shop, animal, buyer, price, counter);
            if (reason != null) {
                cancel(level, shop, animal, buyer, price, reason);
                return true;
            }
            if (age < JOY) return false;
            if ((age - JOY) % 20 == 0 && ShopCourier.dispatchPurchase(level, clerk, animal, buyer, price, counter)) return true;
            if (age < JOY + KEEPER_WAIT) return false;
            cancel(level, shop, animal, buyer, price, "staff_busy");
            return true;
        }, () -> unlock(animal, shop, buyer, price));
        return true;
    }

    private static UUID holder(UUID shop, UUID buyer, int price) { return price == 0 ? shop : buyer; }

    private static boolean ownsHold(YukkuriEntity animal, UUID shop, UUID buyer, int price) {
        return animal.pet().busyWith().equals("sale") && holder(shop, buyer, price).equals(animal.pet().lockedBy());
    }

    static void unlock(YukkuriEntity animal, UUID shop, UUID buyer, int price) {
        if (ownsHold(animal, shop, buyer, price)) animal.pet().unlock("sale");
    }

    private static ServerPlayer nearbyBuyer(ServerLevel level, UUID buyer, Vec3 at) {
        var player = level.getServer().getPlayerList().getPlayer(buyer);
        return player != null && player.level() == level && player.isAlive() && !player.isSpectator()
                && player.distanceToSqr(at) <= COUNTER_RANGE_SQR ? player : null;
    }

    static String unavailable(ServerLevel level, UUID shop, YukkuriEntity animal, UUID buyer, int price, BlockPos counter) {
        if (!(level.getEntity(shop) instanceof HumanNpc clerk) || !clerk.isAlive()) return "shopkeeper_unavailable";
        if (animal == null || level.getEntity(animal.getUUID()) != animal || !animal.isAlive()) return "target_unavailable";
        if (animal.isPassenger() || animal.isVehicle() || !ShopMenu.stockOf(animal, shop)
                || animal.distanceToSqr(Vec3.atBottomCenterOf(counter)) > 48 * 48) return "stock_moved";
        if (!ownsHold(animal, shop, buyer, price)) return "sale_ended";
        return price > 0 && nearbyBuyer(level, buyer, Vec3.atBottomCenterOf(counter)) == null ? "buyer_left" : null;
    }

    static void cancel(ServerLevel level, UUID shop, YukkuriEntity animal, UUID buyer, int price, String reason) {
        if (animal != null) unlock(animal, shop, buyer, price);
        var player = level.getServer().getPlayerList().getPlayer(buyer);
        if (price > 0 && player != null) player.displayClientMessage(Component.translatable("human.mcyukkuri.unavailable"), true);
        GameplayLog.world(level, "shop", "action=buy_live result=cancelled shop={} buyer={} target={} reason={} emeralds_used=0",
                shop, buyer, animal == null ? null : animal.getUUID(), reason);
    }

    /** Validation, capture, payment and the keeper's loaded hand all happen on the same server tick. */
    static UUID capture(ServerLevel level, HumanNpc staff, UUID shop, YukkuriEntity animal, UUID buyer, int price, BlockPos counter) {
        var player = price == 0 ? null : nearbyBuyer(level, buyer, Vec3.atBottomCenterOf(counter));
        if (price > 0 && (player == null || ShopMenu.count(player, Items.EMERALD) < price)) {
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
            ShopMenu.debit(player, Items.EMERALD, price);
            SurvivalProgress.award(player, "pet_bought");
        }
        ShopRegistry.get(level).sold(shop, animal.getUUID());
        GameplayLog.event(staff, "shop", "action=buy_live result=committed shop={} buyer={} target={} token={} emeralds_used={}",
                shop, buyer, animal.getUUID(), token, price);
        return token;
    }

    static boolean delivered(ServerLevel level, HumanNpc staff, UUID buyer, UUID id, UUID token, int price, ItemStack basket, BlockPos counter) {
        boolean atCounter = staff.blockPosition().equals(counter) && staff.onGround();
        var player = atCounter ? nearbyBuyer(level, buyer, Vec3.atBottomCenterOf(counter)) : null;
        boolean received = player != null && player.getInventory().add(basket);
        if (!received && drop(level, buyer, staff.position(), basket) == null) return false;
        GameplayLog.event(staff, "shop", "action=buy_live result=delivered buyer={} target={} token={} price={} mode={} placement={} counter={} handoff_at={}",
                buyer, id, token, price, atCounter ? "keeper" : "fallback", received ? "inventory" : "ground",
                counter.toShortString(), staff.blockPosition().toShortString());
        return true;
    }

    static ItemEntity drop(ServerLevel level, UUID buyer, Vec3 at, ItemStack basket) {
        var item = ModRandom.created(new ItemEntity(level, at.x, at.y + .15, at.z, basket), ModRandom.world(level));
        item.setTarget(buyer);
        item.setDefaultPickUpDelay();
        item.setUnlimitedLifetime();
        return level.addFreshEntity(item) ? item : null;
    }
}
