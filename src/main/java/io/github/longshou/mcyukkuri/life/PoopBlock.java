package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.house.SupplyPiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Droppings on the ground (嗯嗯): a non-solid heap that bodies walk through, holding up to {@link Poops#CAPACITY} real
 * portions. {@link #TIER} (1 to 4) follows the count and picks the art; the heap itself is drawn by the block entity
 * renderer from the care-prop 便堆 mesh until tiered block models exist (docs/TODO.md 美术).
 */
public final class PoopBlock extends Block implements EntityBlock {
    public static final IntegerProperty TIER = IntegerProperty.create("tier", 1, Poops.TIERS);
    private static final VoxelShape[] OUTLINES = {
            box(3, 0, 3, 13, 2, 13), box(2, 0, 2, 14, 4, 14), box(1, 0, 1, 15, 6, 15), box(0, 0, 0, 16, 8, 16)};
    public PoopBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIER, 1));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(TIER); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PoopBlockEntity(pos, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return OUTLINES[state.getValue(TIER) - 1]; }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return type == PathComputationType.LAND; }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) { return SupplyPiles.supported(level, pos); }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState() : super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }
    /** Empty hand: shows the load; sneaking gathers every portion back as the real waste item. */
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PoopBlockEntity poop) {
            if (player.isSecondaryUseActive()) {
                int taken = 0;
                for (var portion : poop.drain()) { SupplyPiles.give(player, portion); taken++; }
                level.removeBlock(pos, false);
                player.displayClientMessage(Component.translatable("care.mcyukkuri.poop.gathered", taken), true);
            } else player.displayClientMessage(Component.translatable("care.mcyukkuri.poop.level", poop.count(), Poops.CAPACITY), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    /** Whatever removes the heap, the portions come out as real waste items; none is lost or made. */
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof PoopBlockEntity poop)
            for (var portion : poop.drain()) Block.popResource(level, pos, portion);
        super.onRemove(state, level, pos, replacement, moving);
    }
}
