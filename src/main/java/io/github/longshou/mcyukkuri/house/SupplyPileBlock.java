package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.item.SupplyItem;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The whole thin ground cell is targetable; its small contents never obstruct a house resident. */
public final class SupplyPileBlock extends Block implements EntityBlock {
    private static final VoxelShape OUTLINE=box(0,0,0,16,1.5,16);
    public SupplyPileBlock(Properties properties){super(properties);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new SupplyPileBlockEntity(pos,state);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.INVISIBLE;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return OUTLINE;}
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return Shapes.empty();}
    @Override protected boolean isPathfindable(BlockState state,PathComputationType type){return type==PathComputationType.LAND;}
    @Override protected boolean canSurvive(BlockState state,LevelReader level,BlockPos pos){return SupplyPiles.supported(level,pos);}
    @Override protected BlockState updateShape(BlockState state,Direction direction,BlockState neighbor,LevelAccessor level,BlockPos pos,BlockPos neighborPos){
        return !canSurvive(state,level,pos)?Blocks.AIR.defaultBlockState():super.updateShape(state,direction,neighbor,level,pos,neighborPos);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(stack.is(ModItems.WASH_CLOTH.get()))return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if(SupplyPiles.waste(stack)){
            if(!level.isClientSide)SupplyItem.deposit(level,pos,stack,player);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!player.getMainHandItem().isEmpty())return InteractionResult.PASS;
        if(!level.isClientSide&&level.getBlockEntity(pos) instanceof SupplyPileBlockEntity pile){
            // Lint and crumbs are only dust and leftovers on the floor; only the cleaned-up waste can be gathered.
            if(player.isSecondaryUseActive()&&pile.is(ModItems.WASTE.get()))SupplyPiles.give(player,pile.takeAll());
            else player.displayClientMessage(Component.translatable("house.mcyukkuri.supplies.inspect",pile.contents().getHoverName(),pile.count(),SupplyPileBlockEntity.CAPACITY),true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(level.getBlockEntity(pos) instanceof SupplyPileBlockEntity pile)pile.decay(level.getGameTime());
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moving){
        if(!state.is(replacement.getBlock())&&!level.isClientSide&&level.getBlockEntity(pos) instanceof SupplyPileBlockEntity pile){
            var stack=pile.drain();if(SupplyPiles.waste(stack))Block.popResource(level,pos,stack);
        }
        super.onRemove(state,level,pos,replacement,moving);
    }
}
