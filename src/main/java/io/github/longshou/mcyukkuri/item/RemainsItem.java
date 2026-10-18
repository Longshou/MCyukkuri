package io.github.longshou.mcyukkuri.item;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
public final class RemainsItem extends Item {
    public RemainsItem(Properties properties){super(properties);}
    @Override public boolean onEntityItemUpdate(net.minecraft.world.item.ItemStack stack,net.minecraft.world.entity.item.ItemEntity original){
        if(original.level().isClientSide||!original.isAlive())return false;
        var corpse=io.github.longshou.mcyukkuri.registry.ModEntities.REMAINS.get().create(original.level());
        if(corpse==null)return false;
        corpse.setRemains(stack);corpse.moveTo(original.getX(),original.getY(),original.getZ(),original.getYRot(),0);corpse.setDeltaMovement(original.getDeltaMovement());
        if(!original.level().addFreshEntity(corpse))return false;
        original.discard();return true;
    }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,TooltipContext context,java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag flag){
        lines.add(net.minecraft.network.chat.Component.translatable("item.mcyukkuri.yukkuri_remains.help"));
        var data=stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        if(data.getString("Cause").equals("minecraft:starve")&&data.getString("CauseDetail").equals("stem_nutrition_depletion"))
            lines.add(net.minecraft.network.chat.Component.translatable("item.mcyukkuri.yukkuri_remains.cause.stem_nutrition_depletion"));
    }
    @Override public InteractionResult useOn(UseOnContext context){
        var level=context.getLevel();var top=context.getClickedPos().relative(context.getClickedFace());var player=context.getPlayer();
        if(context.getClickedFace()!=Direction.UP||!level.isEmptyBlock(top)||player==null||!player.mayBuild()
                ||!level.mayInteract(player,top)||!player.mayUseItemAt(top,Direction.UP,context.getItemInHand()))return InteractionResult.FAIL;
        if(!level.isClientSide){
            if(!io.github.longshou.mcyukkuri.entity.RemainsEntity.place(level,context.getClickLocation().add(0,.002,0),player.getYRot(),context.getItemInHand(),true))return InteractionResult.FAIL;
            // Creative use restores the count on the original stack after useOn returns.
            // Replace the hand slot so placing a body transfers it in either game mode.
            var remainder=context.getItemInHand().copy();remainder.shrink(1);
            player.setItemInHand(context.getHand(),remainder);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
