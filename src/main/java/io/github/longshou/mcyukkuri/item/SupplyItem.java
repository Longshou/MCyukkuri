package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.PoopBlockEntity;
import io.github.longshou.mcyukkuri.life.Poops;
import io.github.longshou.mcyukkuri.settlement.GroupPits;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
        int accepted;
        var contents = level.getBlockEntity(pos);
        if (contents instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.TOILET) {
            accepted = Poops.fittingCount(stack, limit, care.capacity() - care.waste());
            if (accepted > 0 && !care.relieve(stack.copyWithCount(accepted))) accepted = 0;
        } else if (level instanceof ServerLevel server && (contents instanceof PoopBlockEntity
                || contents == null && GroupPits.managedCell(server, pos))) {
            accepted = Poops.deposit(server, pos, stack, limit);
        } else accepted = SupplyPiles.offer(level,pos,stack,limit);
        if(accepted>0){
            double volume=DetachedItems.volume(stack.copyWithCount(accepted));
            if(player==null||!player.getAbilities().instabuild)stack.shrink(accepted);
            if(player!=null)player.displayClientMessage(Component.translatable("care.mcyukkuri.waste.placed",volume),true);
        }else if(player!=null)player.displayClientMessage(Component.translatable("house.mcyukkuri.supplies.blocked"),true);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        if(!SupplyPiles.waste(context.getItemInHand()))return InteractionResult.PASS;
        var level=context.getLevel();var clicked=context.getClickedPos();var player=context.getPlayer();
        var contents = level.getBlockEntity(clicked);
        boolean pile = contents instanceof SupplyPileBlockEntity || contents instanceof PoopBlockEntity
                || contents instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.TOILET;
        if(!pile&&context.getClickedFace()!=Direction.UP)return InteractionResult.PASS;
        var target=pile?clicked:clicked.above();
        if(player!=null&&(!player.mayBuild()||!player.mayUseItemAt(target,context.getClickedFace(),context.getItemInHand())))return InteractionResult.FAIL;
        if(!level.isClientSide)deposit(level,target,context.getItemInHand(),player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag){
        if(SupplyPiles.waste(stack)){
            tooltip.add(Component.translatable("care.mcyukkuri.waste.volume",DetachedItems.volume(stack)));
            tooltip.add(Component.translatable("house.mcyukkuri.supplies.waste_sink"));
            tooltip.add(Component.translatable("house.mcyukkuri.supplies.controls"));
        }else tooltip.add(Component.translatable("house.mcyukkuri.supplies.internal"));
    }
}
