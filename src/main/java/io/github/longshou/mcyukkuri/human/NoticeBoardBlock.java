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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A wall-mounted town notice board with flyers (art/props/notice-board), decorative and without a screen. Like a wall sign it
 * faces {@link #FACING}, hangs against a supporting face and drops when that support goes.
 */
public final class NoticeBoardBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final Map<Direction, VoxelShape> SHAPES = shapes();

    public NoticeBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    private static Map<Direction, VoxelShape> shapes() {
        var out = new EnumMap<Direction, VoxelShape>(Direction.class);
        for (var facing : Direction.Plane.HORIZONTAL) {
            // Backing and pen ledge, then the bounds of the sloped roof in the block model.
            out.put(facing, Shapes.or(wallBox(facing, 1, 3), wallBox(facing, 12.5, 5.25)));
        }
        return out;
    }

    private static VoxelShape wallBox(Direction facing, double bottom, double depth) {
        return switch (facing) {
            case NORTH -> box(0, bottom, 16 - depth, 16, 16, 16);
            case EAST -> box(0, bottom, 0, depth, 16, 16);
            case SOUTH -> box(0, bottom, 0, 16, 16, depth);
            case WEST -> box(16 - depth, bottom, 0, 16, 16, 16);
            default -> throw new IllegalArgumentException("Notice board requires a horizontal facing");
        };
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
        var facing = state.getValue(FACING);
        return canSupportCenter(level, pos.relative(facing.getOpposite()), facing);
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
