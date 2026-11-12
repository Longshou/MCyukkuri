package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The sale of one shelf individual, shared by the shopkeeper menu and the operator command ({@code /yukkuri human shop buy}). Payment is the caller's
 * business (the menu debits emeralds from the buyer); this runs the sale itself. The individual is locked and hops for joy; then a keeper walks to it
 * with a transport basket, it climbs in where it stands and the keeper carries the basket to the counter, where the buyer receives it
 * ({@link ShopCourier}). A shop without a keeper hands over at the counter: the clerk holds out a basket and the animal climbs in. The same entity
 * keeps its UUID, family, personality, body and badge; ownership moves to the buyer, the badge token serial is unchanged because it never left the
 * animal, and the carrier lands in the buyer's inventory (or on the ground at the handover when the buyer is offline). The shop register drops
 * the individual. Whenever the sale cannot finish before the animal is in the basket, the {@code price} is returned to the buyer as emeralds.
 */
public final class ShopTrade {
    private ShopTrade() {}
    /** Ticks of joy before the basket; how long a sale waits for a busy keeper before the clerk hands over himself; the climb into the basket. */
    private static final int JOY = 30, KEEPER_WAIT = 400, CLIMB = 16;

    /**
     * Starts the sale of {@code animal} on {@code clerk}'s shelves to the player with UUID {@code buyer}. Returns false (and changes nothing) when the animal
     * is not this shopkeeper's stock or is busy.
     */
    public static boolean begin(ServerLevel level, HumanNpc clerk, YukkuriEntity animal, UUID buyer, int price) {
        String reason=!animal.isAlive()?"not_alive":animal.isPassenger()?"passenger":!ShopMenu.stockOf(animal,clerk.getUUID())?"not_stock":!animal.pet().lock("sale",buyer)?"busy":null;
        if(reason!=null){
            GameplayLog.event(clerk,"shop","action=buy_live result=refused buyer={} target={} reason={}",buyer,animal.getUUID(),reason);
            return false;
        }
        GameplayLog.event(clerk,"shop","action=buy_live result=accepted buyer={} target={} refundable_price={}",buyer,animal.getUUID(),price);
        animal.playAction("joy_jump"); animal.showReaction("happy", "shop_bought", 40);
        UUID id = animal.getUUID(); Vec3 at = animal.position();
        int[] counter = {-1}; // the age at which the clerk's own handover began
        ShopPerformance.schedule(level, Set.of(clerk.getUUID(), id), age -> {
            if (age < JOY) return false;
            if (!(level.getEntity(id) instanceof YukkuriEntity a) || !a.isAlive()) {
                ShopMenu.handBack(clerk); refund(level, clerk, buyer, id, price, at, "target_unavailable");
                return true;
            }
            if (counter[0] < 0) {
                int courier = ShopCourier.dispatchPurchase(level, clerk, a, buyer, price);
                if (courier > 0) return true; // the keeper's basket finishes the sale
                if (courier == 0 && age < JOY + KEEPER_WAIT) return false;
                counter[0] = age;
                // handover at the counter: the clerk holds out an empty basket and the animal climbs in
                a.playAction("basket_in"); a.speak("basket_enter"); clerk.swing(InteractionHand.MAIN_HAND);
                if (clerk.getMainHandItem().isEmpty()) clerk.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CARRIER.get()));
                return false;
            }
            if (age < counter[0] + CLIMB) return false;
            ShopMenu.handBack(clerk);
            UUID token = capture(level, clerk, clerk.getUUID(), a, buyer, price);
            if (token != null) delivered(level, clerk, buyer, id, token, price, HumanCommerce.carrier(a, token), at, "counter");
            return true;
        }, () -> { animal.pet().unlock("sale"); ShopMenu.handBack(clerk); });
        return true;
    }

    /** The bought animal goes into the basket: it changes hands (custody to the buyer) and is captured in the same step. Null, with the
     *  custody restored and the price refunded, when the capture is refused. {@code staff} is whoever holds the basket (for the log). */
    static UUID capture(ServerLevel level, Entity staff, UUID shop, YukkuriEntity a, UUID buyer, int price) {
        a.pet().unlock("sale");
        var previous = HumanCommerce.data(a).copy();
        Custody.assign(a, buyer, Custody.OwnerKind.PLAYER, Custody.Role.COMPANION);
        UUID token = YukkuriWorldData.get(level).capture(a);
        if (token == null) {
            Custody.restore(a, previous);
            refund(level, staff, buyer, a.getUUID(), price, a.position(), "capture_failed");
            return null;
        }
        ShopRegistry.get(level).sold(shop, a.getUUID());
        return token;
    }

    /** The loaded basket reaches the buyer (inventory, or the ground at {@code at} when offline). */
    static void delivered(ServerLevel level, Entity staff, UUID buyer, UUID id, UUID token, int price, ItemStack basket, Vec3 at, String mode) {
        ShopMenu.deliver(level, buyer, at, basket);
        GameplayLog.event(staff,"shop","action=buy_live result=delivered buyer={} target={} token={} price={} mode={}",buyer,id,token,price,mode);
        var buyerPlayer = level.getServer().getPlayerList().getPlayer(buyer);
        if (buyerPlayer != null) SurvivalProgress.award(buyerPlayer, "pet_bought");
    }

    /** {@code staff} is whoever is left to log it (null when nobody of the sale is loaded any more). */
    static void refund(ServerLevel level, Entity staff, UUID buyer, UUID id, int price, Vec3 at, String reason) {
        ShopMenu.deliverEmeralds(level, buyer, at, price);
        String line = "action=buy_live result=refunded buyer={} target={} reason={} emeralds={}";
        if (staff == null) GameplayLog.world(level, "shop", line, buyer, id, reason, price);
        else GameplayLog.event(staff, "shop", line, buyer, id, reason, price);
    }
}
