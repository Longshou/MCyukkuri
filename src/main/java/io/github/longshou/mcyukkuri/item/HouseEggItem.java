package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.house.HouseEggs;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import java.util.List;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** A single unborn individual. Portable eggs pause; only contact care in a supplied nest advances them. */
public final class HouseEggItem extends Item {
    public HouseEggItem(Properties properties){super(properties);}
    @Override public InteractionResult useOn(UseOnContext context) {
        if(!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof HouseNestBlockEntity nest))return InteractionResult.PASS;
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
        if(!nest.insertEgg(context.getItemInHand())){
            if(context.getPlayer()!=null)context.getPlayer().displayClientMessage(Component.translatable("house.mcyukkuri.egg_blocked"),true);
            return InteractionResult.FAIL;
        }
        // Consume even in creative: the ticket has transferred, so its old stack is no longer an egg.
        var remainder=context.getItemInHand().copy();remainder.shrink(1);
        if(context.getPlayer()!=null)context.getPlayer().setItemInHand(context.getHand(),remainder);
        else context.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }
    @Override public void inventoryTick(ItemStack stack,Level level,Entity carrier,int slot,boolean selected) {
        if(level instanceof ServerLevel server&&carrier.tickCount%40==0){
            var entry=HouseEggs.ticket(server,stack);
            if(entry!=null){
                var data=io.github.longshou.mcyukkuri.family.FamilyData.get(server);
                var person=data.person(entry.child().id);
                if(person!=null){person.position=GlobalPos.of(level.dimension(),carrier.blockPosition());data.setDirty();}
            }
        }
    }
    @Override public boolean onEntityItemUpdate(ItemStack stack,net.minecraft.world.entity.item.ItemEntity item) {
        inventoryTick(stack,item.level(),item,0,false);return false;
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag) {
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        tooltip.add(Component.translatable(tag.hasUUID("EggToken")?"house.mcyukkuri.egg_paused":"house.mcyukkuri.egg_empty",tag.getInt("Progress")));
    }
}
