package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** A normal inventory stack with deliberate, persistent ground placement instead of mandatory Q-dropping. */
public final class SupplyItem extends Item {
    public SupplyItem(Properties properties){super(properties);}
    public static void deposit(Level level,BlockPos pos,ItemStack stack,Player player){
        if(player!=null&&(!player.mayBuild()||!player.mayUseItemAt(pos,Direction.UP,stack)))return;
        int limit=player!=null&&player.isSecondaryUseActive()?8:1;
        int accepted=0;
        if(level.getBlockEntity(pos) instanceof HouseNestBlockEntity nest){
            if(stack.is(ModItems.FOOD_SCRAP.get())||HouseNestBlockEntity.isMaterial(stack))
                for(int i=0;i<Math.min(limit,stack.getCount())&&nest.deposit(stack.copyWithCount(1));i++)accepted++;
        }else accepted=SupplyPiles.offer(level,pos,stack,limit);
        if(accepted>0){
            if(player==null||!player.getAbilities().instabuild)stack.shrink(accepted);
            if(player!=null)player.displayClientMessage(Component.translatable("house.mcyukkuri.supplies.placed",accepted),true);
        }else if(player!=null)player.displayClientMessage(Component.translatable("house.mcyukkuri.supplies.blocked"),true);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        var level=context.getLevel();var clicked=context.getClickedPos();var player=context.getPlayer();
        boolean nest=level.getBlockEntity(clicked) instanceof HouseNestBlockEntity;
        boolean pile=level.getBlockEntity(clicked) instanceof SupplyPileBlockEntity;
        if(nest&&!context.getItemInHand().is(ModItems.FOOD_SCRAP.get())&&!HouseNestBlockEntity.isMaterial(context.getItemInHand()))return InteractionResult.PASS;
        if(!nest&&!pile&&context.getClickedFace()!=Direction.UP)return InteractionResult.PASS;
        var target=nest||pile?clicked:clicked.above();
        if(player!=null&&(!player.mayBuild()||!player.mayUseItemAt(target,context.getClickedFace(),context.getItemInHand())))return InteractionResult.FAIL;
        if(!level.isClientSide)deposit(level,target,context.getItemInHand(),player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag){
        tooltip.add(Component.translatable(stack.is(ModItems.NEST_LINT.get())?"house.mcyukkuri.supplies.lint_source":stack.is(ModItems.FOOD_SCRAP.get())?"house.mcyukkuri.supplies.food_source":"house.mcyukkuri.supplies.waste_sink"));
        tooltip.add(Component.translatable("house.mcyukkuri.supplies.controls"));
    }
}
