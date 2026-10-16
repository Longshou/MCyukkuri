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

/** A visible, player-placeable crevice. The open front is a real small-body clearance. */
public final class HouseNestBlock extends Block implements EntityBlock {
    private static final VoxelShape SHAPE=Shapes.or(box(.5,0,2.02375,15.5,.4,15.02375),
            box(.5,.2,13.92375,15.5,9.8,14.52375),box(.5,0,1.02375,3.5,9,14.02375),
            box(12.5,0,1.02375,15.5,9,14.02375),box(3.5,6.8,1.02375,12.5,9,14.02375));
    public HouseNestBlock(Properties properties){super(properties);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new HouseNestBlockEntity(pos,state);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return SHAPE;}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){
        return !level.isClientSide&&type==ModBlocks.HOUSE_NEST_ENTITY.get()?(world,pos,block,entity)->((HouseNestBlockEntity)entity).tick():null;
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(stack.is(ModItems.WASH_CLOTH.get()))return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if(stack.is(ModItems.HOUSE_EGG.get()))return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if(level.getBlockEntity(pos) instanceof HouseNestBlockEntity nest&&(HouseNestBlockEntity.isMaterial(stack)||stack.is(ModItems.FOOD_SCRAP.get()))){
            if(!level.isClientSide&&nest.deposit(stack.copyWithCount(1))&&!player.getAbilities().instabuild)stack.shrink(1);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
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
