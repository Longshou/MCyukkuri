package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.house.SupplyPiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
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

/** The wild stock pile: a low, walk-through heap on a leaf bed whose inventory is the nest's real food store. */
public final class ForagePileBlock extends Block implements EntityBlock {
    private static final VoxelShape OUTLINE = box(1, 0, 1, 15, 6, 15);
    public ForagePileBlock(Properties properties) { super(properties); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ForagePileBlockEntity(pos, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return OUTLINE; }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return type == PathComputationType.LAND; }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) { return SupplyPiles.supported(level, pos); }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState() : super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }
    /** A player may add food by hand, one portion per use or up to eight while sneaking. */
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ForagePileBlockEntity pile) || pile.foodSpace(stack) == 0) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) {
            int portions = Math.min(stack.getCount(), player.isSecondaryUseActive() ? 8 : 1);
            var rest = pile.returnFood(stack.copyWithCount(portions));
            if (!player.getAbilities().instabuild) stack.shrink(portions - rest.getCount());
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    /** Empty hand inspects; sneaking with an empty hand takes one portion, as any other raider would. */
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ForagePileBlockEntity pile) {
            if (player.isSecondaryUseActive()) {
                var portion = pile.raid();
                if (!portion.isEmpty() && !player.getInventory().add(portion)) player.drop(portion, false);
            } else player.displayClientMessage(Component.translatable("shelter.mcyukkuri.pile.inspect", pile.food(), pile.capacity()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    /** Breaking the block, or losing its footing, drops every real portion it still holds. */
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof ForagePileBlockEntity pile)
            for (var stack : pile.drain()) Containers.dropItemStack(level, pos.getX() + .5, pos.getY() + .1, pos.getZ() + .5, stack);
        super.onRemove(state, level, pos, replacement, moving);
    }
}
