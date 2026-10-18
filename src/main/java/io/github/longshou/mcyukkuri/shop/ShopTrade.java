package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The sale of one shelf individual, shared by the shopkeeper menu and the operator command ({@code /yukkuri human shop sell}). Payment is the caller's
 * business (the menu debits emeralds from the buyer); this runs the sale itself: the individual is locked, the clerk holds out a basket, and 46 ticks later
 * the individual changes hands. The same entity keeps its UUID, family, personality, body and badge; ownership moves to the buyer (custody data and
 * {@code pet().newOwner}), the badge token serial is unchanged because it never left the animal, and the carrier lands in the buyer's inventory (or on the
 * ground where the sale happened when the buyer is offline). The shop register drops the individual.
 */
public final class ShopTrade {
    private ShopTrade() {}

    /**
     * Starts the sale of {@code animal} on {@code clerk}'s shelves to the player with UUID {@code buyer}. Returns false (and changes nothing) when the animal
     * is not this shopkeeper's stock or is busy. If the sale cannot finish, the {@code price} is returned to the buyer as emeralds.
     */
    public static boolean begin(ServerLevel level, HumanNpc clerk, YukkuriEntity animal, UUID buyer, int price) {
        if (!animal.isAlive() || animal.isPassenger() || !ShopMenu.stockOf(animal, clerk.getUUID()) || !animal.pet().lock("sale", buyer)) return false;
        animal.playAction("joy_jump"); animal.showReaction("happy", "shop_bought", 40);
        var neighbours = level.getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(6), o -> o != animal && o.isAlive() && !o.pet().busy() && ShopMenu.stockOf(o, clerk.getUUID()));
        if (!neighbours.isEmpty()) {
            var o = neighbours.get(animal.getRandom().nextInt(neighbours.size()));
            if (o.pet().grade().id() < animal.pet().grade().id()) o.showReaction("teary", "badge_envy", 60);
            else { o.playAction("beg"); o.speak("shop_appeal"); }
        }
        UUID id = animal.getUUID(); Vec3 at = animal.position();
        ShopPerformance.schedule(level, age -> {
            if (age < 30) return false;
            if (!(level.getEntity(id) instanceof YukkuriEntity a) || !a.isAlive()) { ShopMenu.handBack(clerk); ShopMenu.deliverEmeralds(level, buyer, at, price); return true; }
            if (age == 30) { // handover: the clerk holds out an empty basket and the animal climbs in
                a.playAction("basket_in"); a.speak("basket_enter"); clerk.swing(InteractionHand.MAIN_HAND);
                if (clerk.getMainHandItem().isEmpty()) clerk.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CARRIER.get()));
                return false;
            }
            if (age < 46) return false;
            ShopMenu.handBack(clerk);
            a.pet().unlock("sale");
            var previous = HumanCommerce.data(a).copy();
            HumanCommerce.assign(a, buyer, "companion");
            UUID token = YukkuriWorldData.get(level).capture(a);
            if (token == null) { HumanCommerce.set(a, previous); ShopMenu.deliverEmeralds(level, buyer, at, price); return true; }
            ShopRegistry.get(level).sold(clerk.getUUID(), id);
            ShopMenu.deliver(level, buyer, at, HumanCommerce.carrier(a, token));
            var buyerPlayer = level.getServer().getPlayerList().getPlayer(buyer);
            if (buyerPlayer != null) io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(buyerPlayer, "pet_bought");
            return true;
        });
        return true;
    }
}
