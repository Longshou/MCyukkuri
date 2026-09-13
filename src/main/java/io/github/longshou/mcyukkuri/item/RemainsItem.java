package io.github.longshou.mcyukkuri.item;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
public final class RemainsItem extends Item {
    public RemainsItem(Properties properties){super(properties);}
    @Override public InteractionResult useOn(UseOnContext context){
        var level=context.getLevel();var soil=context.getClickedPos();var top=soil.above();
        if(context.getClickedFace()!=Direction.UP||!level.getBlockState(soil).is(BlockTags.DIRT)||!level.isEmptyBlock(top)
                ||context.getPlayer()!=null&&!context.getPlayer().mayUseItemAt(top,Direction.UP,context.getItemInHand()))return InteractionResult.FAIL;
        if(!level.isClientSide&&level.setBlockAndUpdate(top,ModBlocks.GRAVE.get().defaultBlockState())){
            if(level.getBlockEntity(top) instanceof io.github.longshou.mcyukkuri.body.GraveBlockEntity grave)
                grave.bury(context.getItemInHand().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag());
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
