package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;

public final class WashClothItem extends Item {
    public WashClothItem(Properties properties) { super(properties); }
    public static int water(ItemStack stack) { return Math.clamp(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt("Water"),0,8); }
    private static void useWater(ItemStack stack) { CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.putInt("Water",water(stack)-1)); }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack=player.getItemInHand(hand);
        var hit=getPlayerPOVHitResult(level,player,ClipContext.Fluid.SOURCE_ONLY);
        if (!level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) { CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.putInt("Water",8)); player.displayClientMessage(Component.translatable("care.mcyukkuri.cloth.wet"),true); }
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if (water(stack)==0) { player.displayClientMessage(Component.translatable("care.mcyukkuri.cloth.dry"),true); return InteractionResult.FAIL; }
        if (!player.level().isClientSide && entity.life().wash(player.getUUID())) useWater(stack);
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        if(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof io.github.longshou.mcyukkuri.house.HouseNestBlockEntity nest){
            if(water(context.getItemInHand())==0)return InteractionResult.FAIL;
            if(!context.getLevel().isClientSide){int removed=nest.clean(context.getPlayer()!=null&&context.getPlayer().isSecondaryUseActive());
                if(removed>0){useWater(context.getItemInHand());var waste=new ItemStack(ModItems.WASTE.get(),removed);
                    if(context.getPlayer()!=null&&!context.getPlayer().getInventory().add(waste))context.getPlayer().drop(waste,false);}}
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof CareBlockEntity care) || care.kind()!=CareBlock.Kind.TOILET) {
            var player=context.getPlayer();
            if(player==null||!player.isSecondaryUseActive()||water(context.getItemInHand())==0)return InteractionResult.PASS;
            if(!context.getLevel().isClientSide){
                var area=new net.minecraft.world.phys.AABB(context.getClickedPos().above()).inflate(1);
                var loose=context.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,area,
                        item->item.isAlive()&&player.distanceToSqr(item)<=25&&player.hasLineOfSight(item)
                                &&(item.getItem().is(ModItems.FILLING.get())||item.getItem().is(ModItems.FOOD_SCRAP.get())||item.getItem().is(ModItems.WASTE.get())));
                if(!loose.isEmpty()){loose.forEach(net.minecraft.world.entity.Entity::discard);useWater(context.getItemInHand());}
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (water(context.getItemInHand())==0) return InteractionResult.FAIL;
        if (!context.getLevel().isClientSide && care.waste()>0) {
            var portions=care.cleanItems();useWater(context.getItemInHand());
            Player player=context.getPlayer();
            for(var waste:portions)if (player!=null && !player.getInventory().add(waste)) player.drop(waste,false);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("care.mcyukkuri.cloth.charges",water(stack)));
    }
}
