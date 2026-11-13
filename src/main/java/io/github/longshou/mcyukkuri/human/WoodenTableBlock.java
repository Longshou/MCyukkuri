package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A wooden table joining the tables beside it; legs stand only at the open corners of the joined top (top face at 16/16). */
public final class WoodenTableBlock extends JoinedFurnitureBlock {
    public WoodenTableBlock(Properties properties) { super(properties); }

    @Override
    protected boolean joins(BlockState self, Direction side, BlockState other) { return other.is(this); }

    /** The top with its aprons (10..16/16), and a 2/16 leg in every corner whose two sides are open. */
    @Override
    protected VoxelShape shapeOf(BlockState state) {
        boolean north = !state.getValue(SIDES.get(Direction.NORTH)), south = !state.getValue(SIDES.get(Direction.SOUTH));
        boolean west = !state.getValue(SIDES.get(Direction.WEST)), east = !state.getValue(SIDES.get(Direction.EAST));
        var shape = Block.box(0, 10, 0, 16, 16, 16);
        if (north && west) shape = Shapes.or(shape, Block.box(1, 0, 1, 3, 10, 3));
        if (north && east) shape = Shapes.or(shape, Block.box(13, 0, 1, 15, 10, 3));
        if (south && west) shape = Shapes.or(shape, Block.box(1, 0, 13, 3, 10, 15));
        if (south && east) shape = Shapes.or(shape, Block.box(13, 0, 13, 15, 10, 15));
        return shape;
    }

    /** Walkers route around it: a yukkuri's hat would show through the top if it were sent underneath. */
    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
}
