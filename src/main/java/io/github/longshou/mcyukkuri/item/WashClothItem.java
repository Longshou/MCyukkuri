package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.house.HouseholdEcology;
import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class WashClothItem extends Item {
    public WashClothItem(Properties properties) { super(properties); }
    public static int water(ItemStack stack) { return Math.clamp(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt("Water"),0,8); }
    private static void useWater(ItemStack stack) { CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.putInt("Water",water(stack)-1)); }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack=player.getItemInHand(hand);
        var hit=getPlayerPOVHitResult(level,player,ClipContext.Fluid.SOURCE_ONLY);
        if (!level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) { CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.putInt("Water",8));
                player.displayClientMessage(Component.translatable("care.mcyukkuri.cloth.wet"),true); }
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if (water(stack)==0) { player.displayClientMessage(Component.translatable("care.mcyukkuri.cloth.dry"),true); return InteractionResult.FAIL; }
        if (!player.level().isClientSide && entity.life().wash(player.getUUID())) useWater(stack);
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        if(context.getPlayer()!=null&&!context.getPlayer().mayBuild())return InteractionResult.FAIL;
        if(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof HouseNestBlockEntity nest){
            if(!context.getLevel().isClientSide){
                boolean dismantle=context.getPlayer()!=null&&context.getPlayer().isSecondaryUseActive();
                int removed=nest.clean(dismantle);
                HouseholdEcology.cleaned((ServerLevel)context.getLevel(),context.getClickedPos());
                if(dismantle)context.getLevel().removeBlock(context.getClickedPos(),false);
                if(removed>0){if(water(context.getItemInHand())>0)useWater(context.getItemInHand());var waste=new ItemStack(ModItems.WASTE.get(),removed);
                    if(context.getPlayer()!=null)SupplyPiles.give(context.getPlayer(),waste);
                    else Block.popResource(context.getLevel(),context.getClickedPos(),waste);}}
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof CareBlockEntity care) || care.kind()!=CareBlock.Kind.TOILET) {
            var player=context.getPlayer();
            if(player==null||!player.isSecondaryUseActive())return InteractionResult.PASS;
            if(!context.getLevel().isClientSide){
                var level=context.getLevel();
                var center=level.getBlockEntity(context.getClickedPos()) instanceof SupplyPileBlockEntity?context.getClickedPos():context.getClickedPos().above();
                var area=new AABB(center).inflate(1);
                int removed=0;
                for(var cursor:BlockPos.betweenClosed(center.offset(-1,0,-1),center.offset(1,0,1))){
                    var pos=cursor.immutable();
                    if(!level.hasChunkAt(pos)||!SupplyPiles.supported(level,pos)||!level.getBlockState(pos).getCollisionShape(level,pos).isEmpty())continue;
                    var point=Vec3.atBottomCenterOf(pos).add(0,.025,0);
                    if(player.getEyePosition().distanceToSqr(point)>25||level.clip(new ClipContext(player.getEyePosition(),point,
                            ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player)).getType()!=HitResult.Type.MISS)continue;
                    if(level.getBlockEntity(pos) instanceof SupplyPileBlockEntity pile)removed+=pile.takeAll().getCount();
                    HouseholdEcology.cleaned((ServerLevel)level,pos);
                }
                var loose=context.getLevel().getEntitiesOfClass(ItemEntity.class,area,
                        item->item.isAlive()&&player.distanceToSqr(item)<=25&&player.hasLineOfSight(item)
                                &&((item.getItem().is(ModItems.FILLING.get())&&!FillingResource.isCore(item.getItem()))||SupplyPiles.material(item.getItem())));
                for(var item:loose){removed+=item.getItem().getCount();item.discard();}
                HouseholdEcology.cleaned((ServerLevel)level,center);
                if(removed>0){
                    SupplyPiles.give(player,new ItemStack(ModItems.WASTE.get(),removed));if(water(context.getItemInHand())>0)useWater(context.getItemInHand());
                }
                player.displayClientMessage(Component.translatable("house.mcyukkuri.supplies.cleaned",removed),true);
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
        tooltip.add(Component.translatable("house.mcyukkuri.supplies.sweep"));
    }
}
