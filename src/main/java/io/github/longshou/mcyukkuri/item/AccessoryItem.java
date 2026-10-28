package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
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
        if (!player.level().isClientSide) {
            if (!receive(entity, stack, player.getUUID())) {
                String reason = recipientMismatchReason(entity, stack);
                if (reason == null) reason = entity.life().accessoryReceiptRefusalReason();
                logRefusal(entity, player, reason == null ? "held_shards_not_released" : reason);
                return InteractionResult.FAIL;
            }
            stack.shrink(1);
            entity.operations().animateHat(player,true);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    /** Loose accessories are interchangeable physical items. */
    public static boolean matches(YukkuriEntity entity,ItemStack stack){
        return matchesRecipient(entity,stack)&&entity.life().accessoryReceiptRefusalReason()==null;
    }
    /** Accept one physical item. The caller consumes it only after this succeeds. */
    public static boolean receive(YukkuriEntity entity,ItemStack stack,UUID player){
        return matches(entity,stack)&&entity.life().receiveAccessory(stack,player);
    }
    public static boolean matchesRecipient(YukkuriEntity entity,ItemStack stack){
        return recipientMismatchReason(entity,stack)==null;
    }
    public static String recipientMismatchReason(YukkuriEntity entity,ItemStack stack){
        if(!(stack.getItem() instanceof AccessoryItem)||stack.isEmpty())return "kind";
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(data.getBoolean("Cut"))return "cut";
        return recipientProfileMismatchReason(entity,stack.getItem(),data);
    }
    /** Age and former owner do not restrict an accessory or repair fragment of the same kind. */
    public static String recipientProfileMismatchReason(YukkuriEntity entity,Item accessory,CompoundTag data){
        if(accessory!=ModItems.accessoryFor(entity))return "kind";
        if(!entity.getVariant().assetId().equals(data.getString("Variant")))return "variant";
        return null;
    }
    private static void logRefusal(YukkuriEntity entity,Player player,String reason){
        GameplayLog.limited(entity,"headwear","accessory_receive_refused",20,
                "accessory receive refused reason={} player={}",reason,player.getUUID());
    }
    /** Only type and physical condition distinguish loose items, allowing identical items to stack. */
    public static void markSource(ItemStack stack,YukkuriEntity owner){
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{
            tag.putString("Variant",owner.getVariant().assetId());
            tag.putString("SourceModel",ModItems.accessoryModelFor(owner));
            owner.headwear().writeAccessoryState(tag);
        });
        owner.pet().markAccessory(stack);
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
