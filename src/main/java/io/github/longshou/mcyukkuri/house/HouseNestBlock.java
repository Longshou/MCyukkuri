package io.github.longshou.mcyukkuri.house;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;

/** Loose nesting material in a corner or beside furniture. */
public final class HouseNestBlock extends Block implements EntityBlock {
    public HouseNestBlock(Properties properties){super(properties);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new HouseNestBlockEntity(pos,state);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return box(0,0,0,16,1.5,16);}
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return Shapes.empty();}
    /** The bedding is a destination, never a short cut: outside routes run around it and residents step in by hand (HouseNavigation). */
    @Override protected boolean isPathfindable(BlockState state,net.minecraft.world.level.pathfinder.PathComputationType type){return false;}
    @Override protected boolean canSurvive(BlockState state,net.minecraft.world.level.LevelReader level,BlockPos pos){return SupplyPiles.supported(level,pos);}
    @Override protected BlockState updateShape(BlockState state,net.minecraft.core.Direction direction,BlockState neighbor,net.minecraft.world.level.LevelAccessor level,BlockPos pos,BlockPos neighborPos){
        return !canSurvive(state,level,pos)?net.minecraft.world.level.block.Blocks.AIR.defaultBlockState():super.updateShape(state,direction,neighbor,level,pos,neighborPos);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){
        return !level.isClientSide&&type==ModBlocks.HOUSE_NEST_ENTITY.get()?(world,pos,block,entity)->((HouseNestBlockEntity)entity).tick():null;
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(stack.is(ModItems.WASH_CLOTH.get()))return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if(stack.is(ModItems.HOUSE_EGG.get()))return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide&&level.getBlockEntity(pos) instanceof HouseNestBlockEntity nest){
            if(player.isSecondaryUseActive()&&player.getMainHandItem().isEmpty()){
                var egg=nest.extractEgg();
                if(!egg.isEmpty()){
                    if(!player.getInventory().add(egg))player.drop(egg,false);
                    player.displayClientMessage(Component.translatable("house.mcyukkuri.egg_taken"),true);
                }else player.displayClientMessage(Component.translatable("house.mcyukkuri.no_egg"),true);
            }else player.displayClientMessage(Component.translatable("house.mcyukkuri.inspect",nest.materials(),nest.food(),nest.dirt(),nest.eggs(),Math.round(nest.progress()*100)),false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moving){
        if(!state.is(replacement.getBlock())&&!level.isClientSide&&level.getBlockEntity(pos) instanceof HouseNestBlockEntity nest)nest.destroyNest();
        super.onRemove(state,level,pos,replacement,moving);
    }
}
