package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class AccessoryItem extends Item {
    public AccessoryItem(Properties properties) { super(properties); }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if(player.isSecondaryUseActive()){
            InteractionDispatch.openCare(player,entity);return InteractionResult.sidedSuccess(player.level().isClientSide);
        }
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if (data.getBoolean("Cut") || !data.hasUUID("Owner") || !data.hasUUID("Token")) return InteractionResult.FAIL;
        if (!player.level().isClientSide) {
            if (!entity.life().returnAccessory(data.getUUID("Owner"),data.getUUID("Token"),player.getUUID())) return InteractionResult.FAIL;
            // Returning even in creative transfers this unique object out of the hand.
            stack.shrink(1);
            entity.operations().animateHat(player,true);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    public static boolean matches(YukkuriEntity entity,ItemStack stack){
        if(!(stack.getItem() instanceof AccessoryItem))return false;
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        return !data.getBoolean("Cut")&&data.hasUUID("Owner")&&data.hasUUID("Token")&&entity.life().matchesAccessory(data.getUUID("Owner"),data.getUUID("Token"));
    }
    public static boolean suspend(Player player,YukkuriEntity owner,ItemStack stack){
        if(!matches(owner,stack))return false;
        BlockPos support=null;
        for(int dy=2;dy<=4;dy++){
            var pos=owner.blockPosition().above(dy);
            if(!player.level().getBlockState(pos).getCollisionShape(player.level(),pos).isEmpty()){support=pos;break;}
        }
        if(support==null){player.displayClientMessage(Component.translatable("operation.mcyukkuri.need_ceiling"),true);return false;}
        var one=stack.copyWithCount(1);final var anchor=support;
        CustomData.update(DataComponents.CUSTOM_DATA,one,tag->{tag.putLong("SuspensionAnchor",anchor.asLong());tag.putString("SuspensionDimension",
                player.level().dimension().location().toString());tag.putInt("SuspensionTicks",600);});
        var position=Vec3.atBottomCenterOf(anchor).add(0,-.35,0);
        var drop=new ItemEntity(player.level(),position.x,position.y,position.z,one);
        drop.setDeltaMovement(Vec3.ZERO);drop.setNoGravity(true);drop.setPickUpDelay(20);
        if(!player.level().addFreshEntity(drop))return false;
        stack.shrink(1);return true;
    }
    private static void clearSuspension(ItemStack stack){CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.remove("SuspensionAnchor");
            tag.remove("SuspensionDimension");tag.remove("SuspensionTicks");});}
    @Override public void inventoryTick(ItemStack stack,Level level,Entity carrier,int slot,boolean selected){
        if(!level.isClientSide&&stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).contains("SuspensionAnchor"))clearSuspension(stack);
    }
    @Override public boolean onEntityItemUpdate(ItemStack stack,ItemEntity item){
        if(item.level().isClientSide)return false;
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();if(!tag.contains("SuspensionAnchor"))return false;
        var anchor=BlockPos.of(tag.getLong("SuspensionAnchor"));var position=Vec3.atBottomCenterOf(anchor).add(0,-.35,0);
        int remaining=tag.getInt("SuspensionTicks")-1;
        if(remaining<=0||!item.level().dimension().location().toString().equals(tag.getString("SuspensionDimension"))
                ||item.distanceToSqr(position)>4||item.level().getBlockState(anchor).getCollisionShape(item.level(),anchor).isEmpty()){
            clearSuspension(stack);item.setNoGravity(false);return false;
        }
        item.setNoGravity(true);item.setDeltaMovement(Vec3.ZERO);item.setPos(position);
        CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.putInt("SuspensionTicks",remaining));return false;
    }
}
