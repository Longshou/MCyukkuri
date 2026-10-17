package io.github.longshou.mcyukkuri.item;

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

/**
 * The cleaned-up waste (compostable) may be set down on the ground again. The same class backs the two internal
 * carrier items of nest lint and food crumbs; those are not usable by players at all: they are world state produced
 * by people living in a house and are removed from any inventory or item entity they might reach.
 */
public final class SupplyItem extends Item {
    public SupplyItem(Properties properties){super(properties);}
    public static void deposit(Level level,BlockPos pos,ItemStack stack,Player player){
        if(!SupplyPiles.waste(stack))return;
        if(player!=null&&(!player.mayBuild()||!player.mayUseItemAt(pos,Direction.UP,stack)))return;
        int limit=player!=null&&player.isSecondaryUseActive()?8:1;
        int accepted=SupplyPiles.offer(level,pos,stack,limit);
        if(accepted>0){
            if(player==null||!player.getAbilities().instabuild)stack.shrink(accepted);
            if(player!=null)player.displayClientMessage(Component.translatable("house.mcyukkuri.supplies.placed",accepted),true);
        }else if(player!=null)player.displayClientMessage(Component.translatable("house.mcyukkuri.supplies.blocked"),true);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        if(!SupplyPiles.waste(context.getItemInHand()))return InteractionResult.PASS;
        var level=context.getLevel();var clicked=context.getClickedPos();var player=context.getPlayer();
        boolean pile=level.getBlockEntity(clicked) instanceof SupplyPileBlockEntity;
        if(!pile&&context.getClickedFace()!=Direction.UP)return InteractionResult.PASS;
        var target=pile?clicked:clicked.above();
        if(player!=null&&(!player.mayBuild()||!player.mayUseItemAt(target,context.getClickedFace(),context.getItemInHand())))return InteractionResult.FAIL;
        if(!level.isClientSide)deposit(level,target,context.getItemInHand(),player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag){
        if(SupplyPiles.waste(stack)){
            tooltip.add(Component.translatable("house.mcyukkuri.supplies.waste_sink"));
            tooltip.add(Component.translatable("house.mcyukkuri.supplies.controls"));
        }else tooltip.add(Component.translatable("house.mcyukkuri.supplies.internal"));
    }
}
