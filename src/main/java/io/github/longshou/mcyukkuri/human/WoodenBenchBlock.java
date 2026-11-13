package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A backless village bench (a single one is a stool). It lies along AXIS and joins benches of the same axis at its ends. The seat top is
 * at 8/16 like a slab; every piece is a seat of the street rest spot (settlement/FoundShelter).
 */
public final class WoodenBenchBlock extends JoinedFurnitureBlock {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    public WoodenBenchBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.X));
    }

    /** One long side of the seat; the bench has no back, so a sitter may face either long side. */
    public static Direction front(BlockState state) {
        return Direction.fromAxisAndDirection(state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X,
                Direction.AxisDirection.POSITIVE);
    }

    @Override
    protected boolean joins(BlockState self, Direction side, BlockState other) {
        return side.getAxis() == self.getValue(AXIS) && other.is(this) && other.getValue(AXIS) == self.getValue(AXIS);
    }

    /** The bench runs across the placer's view, so the placer faces its seat. */
    @Override
    protected BlockState placed(BlockPlaceContext context) {
        return defaultBlockState().setValue(AXIS, context.getHorizontalDirection().getClockWise().getAxis());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        var turned = super.rotate(state, rotation);
        if (rotation != Rotation.CLOCKWISE_90 && rotation != Rotation.COUNTERCLOCKWISE_90) return turned;
        return turned.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
    }

    /** Seat board 5..8/16 high and 1..15/16 deep; trestle legs at the open ends, mid-span in a middle piece. */
    @Override
    protected VoxelShape shapeOf(BlockState state) {
        var axis = state.getValue(AXIS);
        boolean before = state.getValue(SIDES.get(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE)));
        boolean after = state.getValue(SIDES.get(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)));
        var shape = along(axis, before ? 0 : 1, 5, 1, after ? 16 : 15, 8, 15);
        if (!before) shape = Shapes.or(shape, legs(axis, 2));
        if (!after) shape = Shapes.or(shape, legs(axis, 12));
        if (before && after) shape = Shapes.or(shape, legs(axis, 7));
        return shape;
    }

    private static VoxelShape legs(Direction.Axis axis, double x) {
        return Shapes.or(along(axis, x, 0, 2, x + 2, 5, 4), along(axis, x, 0, 12, x + 2, 5, 14));
    }

    /** A box given along the bench (x) and across it (z), turned for a bench along z as the blockstate turns the model (y = 90). */
    private static VoxelShape along(Direction.Axis axis, double x0, double y0, double z0, double x1, double y1, double z1) {
        return axis == Direction.Axis.X ? Block.box(x0, y0, z0, x1, y1, z1) : Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(AXIS);
    }
}
