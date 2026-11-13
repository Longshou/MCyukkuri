package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.life.Toxins;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/** Player interaction and leash handling; exact server decisions stay in the existing components. */
final class YukkuriInteractions {
    private final YukkuriEntity owner;
    YukkuriInteractions(YukkuriEntity owner) { this.owner = owner; }
    InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            if(!player.getItemInHand(hand==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND).isEmpty()){
                return owner.mobInteractBase(player,hand);
            }
            if (player instanceof ServerPlayer server) {
                if(player.isSecondaryUseActive())InteractionDispatch.openCare(server,owner);
                else owner.social().clickedTalk(player);
            }
            return InteractionResult.sidedSuccess(owner.level().isClientSide);
        }
        if(Consumables.water(stack))return Consumables.feed(owner,player,hand);
        // Vanilla shears carry every cut (hair, accessory, stem, sterilizing); the menu offers only the cuts that apply now.
        if(stack.is(Tags.Items.TOOLS_SHEAR)&&hand==InteractionHand.MAIN_HAND){
            if(player instanceof ServerPlayer server)InteractionDispatch.openCutter(server,owner);
            return InteractionResult.sidedSuccess(owner.level().isClientSide);
        }
        boolean food = stack.is(YukkuriEntity.FOOD) || Toxins.dose(stack) > 0 || InfantFood.isMash(stack)
                || Consumables.kind(stack) == Consumables.Kind.SPICY || FillingResource.isFilling(stack)
                || owner.profile().diet().eats(stack) || owner.profile().houseBody() && stack.is(ModItems.FOOD_SCRAP.get())
                || stack.is(ModItems.WASTE.get()) && (owner.level().isClientSide || owner.life().acceptsFood(stack));
        if(!food)return owner.mobInteractBase(player,hand);
        if(owner.level().isClientSide)return InteractionResult.SUCCESS;
        if (!ExamSession.allowsPeerFeeding(owner, player.getUUID())) {
            InteractionDispatch.refuse(player, owner, "exam", "busy");
            return InteractionResult.FAIL;
        }
        if (Consumables.kind(stack) == Consumables.Kind.SPICY
                && !InteractionDispatch.requireHarmContact(player, owner, 3, "spicy_feed")) return InteractionResult.FAIL;

        boolean prepared = owner.family().needsMash() && InfantFood.isMash(stack);
        if (!prepared && Toxins.dose(stack) > 0 && !owner.life().acceptsFood(stack)) { // bitter or poisonous: refused unless starving
            GameplayLog.limited(owner,"interaction","food_toxic",20,"food refused; player={} item={} reason=toxic nutrition={}",
                    player.getUUID(),stack.getItem(),owner.life().nutrition());
            owner.showReaction("cheek_puff", "refuse", 60);
            return InteractionResult.CONSUME;
        }
        if(prepared&&!InteractionDispatch.requireReach(player,owner,3))return InteractionResult.FAIL;
        boolean sweet = Sweets.is(stack);
        boolean mayFeed = prepared ? owner.life().canReceiveCareFood(stack)
                : owner.life().acceptsFood(stack) && (owner.life().canEat() || owner.life().canTakeSweet(stack));
        if (!mayFeed) {
            if (sweet && owner.pet().owned(player) && owner.life().acceptsFood(stack) && owner.life().nutrition() > Sweets.FULL) { // too full even for a treat
                GameplayLog.limited(owner,"interaction","food_full",20,"food refused; player={} item={} reason=full nutrition={}",
                        player.getUUID(),stack.getItem(),owner.life().nutrition());
                owner.showReaction("neutral", "snack_refuse", 60);
                return InteractionResult.CONSUME;
            }
            GameplayLog.limited(owner,"interaction","food_unaccepted",20,"food refused; player={} item={} reason=cannot_eat nutrition={}",
                    player.getUUID(),stack.getItem(),owner.life().nutrition());
            owner.showReaction("cheek_puff","refuse",60);
            return InteractionResult.CONSUME;
        }
        int before=stack.getCount();
        ItemStack one = stack.copyWithCount(1);
        boolean fed = prepared ? owner.life().beginPreparedMeal(one, player.getUUID()) : owner.life().beginMeal(one, player.getUUID(), false);
        if (fed && sweet) owner.pet().lessons().reward(player, one);
        if (fed && !player.getAbilities().instabuild) stack.shrink(1);
        if (!fed) owner.showReaction("cheek_puff","refuse",60);
        GameplayLog.event(owner,"interaction","food offered; player={} item={} meal_started={} item_count={} -> {} prepared={}",
                player.getUUID(),one.getItem(),fed,before,stack.getCount(),prepared);
        return InteractionResult.CONSUME;
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
