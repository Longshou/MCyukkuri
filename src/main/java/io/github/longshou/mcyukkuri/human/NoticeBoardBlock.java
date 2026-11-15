package io.github.longshou.mcyukkuri.human;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A wall-mounted town notice board with flyers (art/props/notice-board), decorative and without a screen. Like a wall sign it
 * faces {@link #FACING}, hangs on the solid block behind it and drops when that block goes.
 */
public final class NoticeBoardBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final Map<Direction, VoxelShape> SHAPES = shapes();

    public NoticeBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /** The board, roof and pen ledge against the wall, drawn for a north-facing board and turned clockwise for the others. */
    private static Map<Direction, VoxelShape> shapes() {
        var out = new EnumMap<Direction, VoxelShape>(Direction.class);
        for (var facing : Direction.Plane.HORIZONTAL) {
            double x0 = 0, z0 = 13, x1 = 16, z1 = 16;
            int turns = switch (facing) { case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; default -> 3; };
            for (int i = 0; i < turns; i++) { double a = x0, c = x1; x0 = 16 - z1; x1 = 16 - z0; z0 = a; z1 = c; }
            out.put(facing, box(x0, 1, z0, x1, 16, z1));
        }
        return out;
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel(); var pos = context.getClickedPos();
        for (var look : context.getNearestLookingDirections()) {
            if (!look.getAxis().isHorizontal()) continue;
            var state = defaultBlockState().setValue(FACING, look.getOpposite());
            if (state.canSurvive(level, pos)) return state;
        }
        return null;
    }

    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.relative(state.getValue(FACING).getOpposite())).isSolid();
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                               BlockPos pos, BlockPos neighborPos) {
        return direction == state.getValue(FACING).getOpposite() && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }
}
