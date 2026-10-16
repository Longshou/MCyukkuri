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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Open-front cardboard hiding box; FACING is the open side. Collision is two side walls, the back wall and the roof only.
 * Clear interior: 14/16 wide, 15/16 high. Every ordinary and predator form (width 0.75-0.80, height 0.53-0.60, growth scale <= 1) fits;
 * deibu (growth scale x1.5, width >= 1.1) does not. */
public final class ExamHideBoxBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape[] SHAPES = new VoxelShape[4];
    static {
        for (Direction d : Direction.Plane.HORIZONTAL)
            SHAPES[d.get2DDataValue()] = Shapes.or(rotated(d, 0, 0, 1, 16), rotated(d, 15, 0, 16, 16), rotated(d, 1, 15, 15, 16), box(0, 15, 0, 16, 16, 16));
    }
    /** Walls are authored for an open north side and turned about the block centre. */
    private static VoxelShape rotated(Direction facing, double x0, double z0, double x1, double z1) {
        return switch (facing) {
            case EAST -> box(16 - z1, 0, x0, 16 - z0, 16, x1);
            case SOUTH -> box(16 - x1, 0, 16 - z1, 16 - x0, 16, 16 - z0);
            case WEST -> box(z0, 0, 16 - x1, z1, 16, 16 - x0);
            default -> box(x0, 0, z0, x1, 16, z1);
        };
    }
    public ExamHideBoxBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite()); }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPES[s.getValue(FACING).get2DDataValue()]; }
    @Override protected VoxelShape getOcclusionShape(BlockState s, BlockGetter l, BlockPos p) { return Shapes.empty(); }
}
