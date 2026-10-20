package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.life.Toxins;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Player interaction and leash handling; exact server decisions stay in the existing components. */
final class YukkuriInteractions {
    private final YukkuriEntity owner;
    YukkuriInteractions(YukkuriEntity owner) { this.owner = owner; }
    InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if(Consumables.water(stack))return Consumables.feed(owner,player,hand);
        if (stack.isEmpty()) {
            if (!owner.level().isClientSide) {
                if(player.isSecondaryUseActive())InteractionDispatch.openCare(player,owner);
                else if(!ShopMenu.clickStock(player,owner)&&owner.social().talk(player,true))owner.pet().care().feedback(player,CareAction.GENTLE);
            }
            return InteractionResult.sidedSuccess(owner.level().isClientSide);
        }
        if (stack.is(Items.SHEARS) && player.isSecondaryUseActive()) {
            if (!owner.level().isClientSide) {
                owner.operations().perform(player,CareAction.TAKE_ACCESSORY);
            }
            return InteractionResult.sidedSuccess(owner.level().isClientSide);
        }
        if (owner.level().isClientSide) {
            // Predict item categories only. Exact acceptance belongs to the server's components.
            boolean food = stack.is(YukkuriEntity.FOOD) || Toxins.dose(stack) > 0
                    || Consumables.kind(stack) == Consumables.Kind.SPICY || FillingResource.isFilling(stack)
                    || owner.profile().houseBody() && stack.is(ModItems.FOOD_SCRAP.get());
            return food ? InteractionResult.sidedSuccess(true) : owner.mobInteractBase(player, hand);
        }
        boolean prepared = owner.family().needsMash() && InfantFood.isMash(stack);
        if (!prepared && Toxins.dose(stack) > 0 && !owner.life().acceptsFood(stack)) { // bitter or poisonous: refused unless starving
            GameplayLog.limited(owner,"interaction","food_toxic",20,"food refused; player={} item={} reason=toxic nutrition={}",
                    player.getUUID(),stack.getItem(),owner.life().nutrition());
            if (!owner.level().isClientSide) owner.showReaction("cheek_puff", "refuse", 60);
            return InteractionResult.sidedSuccess(owner.level().isClientSide);
        }
        boolean sweet = Sweets.is(stack);
        boolean mayFeed = prepared ? owner.life().canReceiveCareFood(stack) && player.distanceToSqr(owner) <= 9 && player.hasLineOfSight(owner)
                : owner.life().acceptsFood(stack) && (owner.life().canEat() || owner.life().canTakeSweet(stack));
        if (!mayFeed) {
            if (sweet && owner.pet().owned(player) && owner.life().acceptsFood(stack) && owner.life().nutrition() > Sweets.FULL) { // too full even for a treat
                GameplayLog.limited(owner,"interaction","food_full",20,"food refused; player={} item={} reason=full nutrition={}",
                        player.getUUID(),stack.getItem(),owner.life().nutrition());
                if (!owner.level().isClientSide) owner.showReaction("neutral", "snack_refuse", 60);
                return InteractionResult.sidedSuccess(owner.level().isClientSide);
            }
            return owner.mobInteractBase(player, hand);
        }
        if (!owner.level().isClientSide) {
            int before=stack.getCount();
            ItemStack one = stack.copyWithCount(1);
            boolean fed = prepared ? owner.life().beginPreparedMeal(one, player.getUUID()) : owner.life().beginMeal(one, player.getUUID(), false);
            if (fed && sweet) owner.pet().lessons().reward(player, one);
            if (fed && !player.getAbilities().instabuild) stack.shrink(1);
            GameplayLog.event(owner,"interaction","food offered; player={} item={} meal_started={} item_count={} -> {} prepared={}",
                    player.getUUID(),one.getItem(),fed,before,stack.getCount(),prepared);
        }
        return InteractionResult.sidedSuccess(owner.level().isClientSide);
    }
    boolean canBeLeashed() { return (owner.visibleWear() & 1) != 0; }
    Vec3 getLeashOffset() { return new Vec3(0, owner.getBbHeight() * .13, -owner.getBbWidth() * .46); }
    boolean shouldStayCloseToLeashHolder() {
        return !owner.life().hasUrgentNeed() && !owner.pet().busy() && owner.life().mealTicks() == 0 && !owner.isCommandPlaying() && !owner.operations().active();
    }
    void dropLeash(boolean broadcast, boolean dropItem) {
        var holder = owner.getLeashHolder();
        owner.dropLeashBase(broadcast, false);
        if (dropItem && holder != null && !owner.level().isClientSide) owner.pet().care().returnLead(holder);
    }
}
