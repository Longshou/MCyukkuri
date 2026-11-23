package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Only a committed stem-cut operation creates this holder; breaking it ends its unborn brood. */
public final class DetachedStemBlock extends Block implements EntityBlock {
    public final boolean bottled;
    /** The cut stalk or brood bottle standing at the cell centre; the arched stalk and its fruit leave the cell and cannot be picked. */
    private final VoxelShape outline;
    public DetachedStemBlock(boolean bottled,Properties properties){super(properties);this.bottled=bottled;
        this.outline=bottled?box(4,0,4,12,10,12):box(4,0,4,12,9,12);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return outline;}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new DetachedStemBlockEntity(pos,state);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.ENTITYBLOCK_ANIMATED;}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){
        return !level.isClientSide&&type==ModBlocks.DETACHED_STEM_ENTITY.get()?(world,pos,block,entity)->((DetachedStemBlockEntity)entity).tick():null;
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(level.getBlockEntity(pos) instanceof DetachedStemBlockEntity stem
                &&(level.isClientSide?bottled&&stack.is(Items.SUGAR)&&stem.sugar()<16&&stem.count()>0
                &&Integer.bitCount(stem.born())<stem.count():stem.canSupply(stack))){
            if(!level.isClientSide&&stem.supply(stack)&&!player.getAbilities().instabuild)stack.shrink(1);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide&&level.getBlockEntity(pos) instanceof DetachedStemBlockEntity stem)
            player.displayClientMessage(Component.translatable("family.mcyukkuri.detached",stem.count(),Math.round(stem.progress()*100),stem.sugar(),bottled),false);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moving){
        if(!state.is(replacement.getBlock())&&!level.isClientSide&&level.getBlockEntity(pos) instanceof DetachedStemBlockEntity stem)stem.destroyBrood();
        super.onRemove(state,level,pos,replacement,moving);
    }
}
