package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A loose screen of twigs and leaves a wild Reimu piles across its den mouth (project design, user request; see
 * {@link DenSeal}). It is a physical block, not magic: solid to every body and every path, light enough that a player
 * breaks it by hand. The block entity remembers who laid it and takes it down when the danger has passed.
 */
public final class DenBarrierBlock extends Block implements EntityBlock {
    private static final VoxelShape SHAPE = box(1, 0, 1, 15, 16, 15);

    public DenBarrierBlock(Properties properties) { super(properties); }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
    /** Navigation treats the screen as a wall for walkers and flyers alike. */
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DenBarrierBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == ModBlocks.DEN_BARRIER_ENTITY.get()
                ? (world, pos, block, entity) -> ((DenBarrierBlockEntity) entity).tick() : null;
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server && server.getBlockEntity(pos) instanceof DenBarrierBlockEntity barrier)
            barrier.broken(player);
        return super.playerWillDestroy(level, pos, state, player);
    }
}
