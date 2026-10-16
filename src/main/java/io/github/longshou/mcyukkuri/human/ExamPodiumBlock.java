package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Low answering platform of the exam hall; the candidate stands on top, FACING points toward the examiner desk. */
public final class ExamPodiumBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** 3/16 high, full footprint: a yukkuri walks onto it as a low step. */
    private static final VoxelShape SHAPE = box(0, 0, 0, 16, 3, 16);
    public ExamPodiumBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite()); }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
}
