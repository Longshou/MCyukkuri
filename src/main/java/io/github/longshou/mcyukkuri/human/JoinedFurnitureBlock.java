package io.github.longshou.mcyukkuri.human;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Wooden furniture that joins its neighbours of the same kind into one piece: a joined side has no legs or end board, so a run of
 * benches or a group of tables reads as one bench or one table. Collision follows the drawn boards and legs
 * (art/structures/human-world/author_furniture.py).
 */
public abstract class JoinedFurnitureBlock extends Block {
    public static final Map<Direction, BooleanProperty> SIDES = Map.of(Direction.NORTH, BlockStateProperties.NORTH,
            Direction.EAST, BlockStateProperties.EAST, Direction.SOUTH, BlockStateProperties.SOUTH, Direction.WEST, BlockStateProperties.WEST);
    private final Map<BlockState, VoxelShape> shapes;

    protected JoinedFurnitureBlock(Properties properties) {
        super(properties);
        var state = stateDefinition.any();
        for (var side : SIDES.values()) state = state.setValue(side, false);
        registerDefaultState(state);
        shapes = getShapeForEachState(this::shapeOf);
    }

    /** Whether this piece joins {@code other}, the state next to it on {@code side}. */
    protected abstract boolean joins(BlockState self, Direction side, BlockState other);

    protected abstract VoxelShape shapeOf(BlockState state);

    protected BlockState placed(BlockPlaceContext context) { return defaultBlockState(); }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = placed(context);
        for (var side : SIDES.entrySet())
            state = state.setValue(side.getValue(), joins(state, side.getKey(),
                    context.getLevel().getBlockState(context.getClickedPos().relative(side.getKey()))));
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction side, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        return side.getAxis().isHorizontal() ? state.setValue(SIDES.get(side), joins(state, side, other)) : state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        var turned = state;
        for (var side : SIDES.entrySet()) turned = turned.setValue(SIDES.get(rotation.rotate(side.getKey())), state.getValue(side.getValue()));
        return turned;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        var mirrored = state;
        for (var side : SIDES.entrySet()) mirrored = mirrored.setValue(SIDES.get(mirror.mirror(side.getKey())), state.getValue(side.getValue()));
        return mirrored;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return shapes.get(state); }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SIDES.values().toArray(BooleanProperty[]::new));
    }
}
