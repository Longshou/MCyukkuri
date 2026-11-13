package io.github.longshou.mcyukkuri.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The one-block pet bed in three grades (straw, cushion, velvet; one shape, drafts/care-props/placed.py pet_nest). Its round
 * roll reaches 5.3 past the block on every side; a side whose neighbouring block reaches into that band is flattened flush
 * with the block face, like connected blocks (NORTH..WEST true = flattened), so the roll never pokes into a block. FACING is
 * the entrance (the low lip), chosen at placement toward the placer, else the next open side clockwise; with no open side the
 * bed is not placed. A block set in front of the entrance later turns it to the next open side; enclosed on all four sides it
 * stays where it was, flattened too (then no stand is found outside it).
 */
public final class PetNestBlock extends CareBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH, EAST = BlockStateProperties.EAST,
            SOUTH = BlockStateProperties.SOUTH, WEST = BlockStateProperties.WEST;
    /** How far the roll reaches past its block (NEST_ROLL outer radius 13.3, collision cells to 13.5). */
    private static final double OVERHANG = 5.5 / 16;
    /** Level pad reach and the roll's inner side at body height, from the block centre, on a round and on a flattened side
     *  (placed.py NEST_LEVEL 11.5 and NEST_ROLL row 11.1; a flat side keeps NEST_KEEP .3 of the depth inside NEST_FLAT 8). */
    private static final double LEVEL_ROUND = 11.5 / 16, LEVEL_FLAT = (8 - 1.8 * .3) / 16,
            HOLLOW_ROUND = 11.1 / 16, HOLLOW_FLAT = (8 - 2.2 * .3) / 16;
    private static final VoxelShape[][] COLLISION = new VoxelShape[4][16];
    /** Top of the roll's collision, in blocks. */
    private static final double TOP = authoredTop("pet_nest_0") / 16;
    static {
        for (int mask = 0; mask < 16; mask++) {
            var local = authoredShape("pet_nest_" + mask, 8);
            for (int turns = 0; turns < 4; turns++) COLLISION[turns][mask] = turn(local, .5, .5, turns);
        }
    }
    private final int grade;

    public PetNestBlock(Properties properties, String look, int grade) {
        super(Kind.NEST, properties, look, 8, 0);
        this.grade = grade;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false));
    }
    @Override public int grade() { return grade; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, NORTH, EAST, SOUTH, WEST);
    }

    public static BooleanProperty side(Direction side) {
        return switch (side) { case NORTH -> NORTH; case EAST -> EAST; case SOUTH -> SOUTH; default -> WEST; };
    }
    /** World mask of flattened sides: 1 north, 2 east, 4 south, 8 west. */
    private static int walls(BlockState state) {
        return (state.getValue(NORTH) ? 1 : 0) | (state.getValue(EAST) ? 2 : 0) | (state.getValue(SOUTH) ? 4 : 0) | (state.getValue(WEST) ? 8 : 0);
    }
    /** The same sides in the bed's own frame (entrance north): bit 1 entrance, 2 its clockwise neighbour, 4 back, 8 the other. */
    public static int localWalls(BlockState state) {
        var facing = state.getValue(FACING);
        int mask = 0;
        for (int bit = 0; bit < 4; bit++) {
            Direction side = facing;
            for (int k = 0; k < bit; k++) side = side.getClockWise();
            if (state.getValue(side(side))) mask |= 1 << bit;
        }
        return mask;
    }

    /** Whether the neighbour's collision reaches into the band the roll would overhang on that side, above floor coverings. */
    static boolean blocks(BlockGetter level, BlockPos pos, Direction side) {
        var next = pos.relative(side);
        var shape = level.getBlockState(next).getCollisionShape(level, next);
        if (shape.isEmpty()) return false;
        double near = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0 : 1 - OVERHANG;
        var band = side.getAxis() == Direction.Axis.X ? Shapes.box(near, .125, 0, near + OVERHANG, TOP, 1)
                : Shapes.box(0, .125, near, 1, TOP, near + OVERHANG);
        return Shapes.joinIsNotEmpty(shape, band, BooleanOp.AND);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel(); var pos = context.getClickedPos();
        var state = defaultBlockState();
        for (Direction side : Direction.Plane.HORIZONTAL) state = state.setValue(side(side), blocks(level, pos, side));
        state = open(state.setValue(FACING, context.getHorizontalDirection().getOpposite()));
        if (state.getValue(side(state.getValue(FACING)))) {
            if (level.isClientSide && context.getPlayer() != null)
                context.getPlayer().displayClientMessage(Component.literal("窝四面都被挡住，没有入口：至少留出一侧"), true);
            return null;
        }
        return state;
    }
    /** A block set beside it flattens that side; one set in front of the entrance turns the entrance to the next open side. */
    @Override protected BlockState updateShape(BlockState state, Direction side, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        if (side.getAxis().isHorizontal()) state = open(state.setValue(side(side), blocks(level, pos, side)));
        return super.updateShape(state, side, neighbour, level, pos, neighbourPos);
    }
    /** The entrance on its side if that is open, else on the next open side clockwise; enclosed, it stays where it was. */
    private static BlockState open(BlockState state) {
        Direction entrance = state.getValue(FACING);
        for (int k = 0; k < 4; k++, entrance = entrance.getClockWise())
            if (!state.getValue(side(entrance))) return state.setValue(FACING, entrance);
        return state;
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        var out = state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        for (Direction side : Direction.Plane.HORIZONTAL) out = out.setValue(side(rotation.rotate(side)), state.getValue(side(side)));
        return out;
    }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        var out = state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
        for (Direction side : Direction.Plane.HORIZONTAL) out = out.setValue(side(mirror.mirror(side)), state.getValue(side(side)));
        return out;
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        double reach = OVERHANG * 16;
        return box(state.getValue(WEST) ? 0 : -reach, 0, state.getValue(NORTH) ? 0 : -reach,
                state.getValue(EAST) ? 16 : 16 + reach, TOP * 16, state.getValue(SOUTH) ? 16 : 16 + reach);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION[turns(Direction.NORTH, state.getValue(FACING))][localWalls(state)];
    }

    /**
     * Where bodies rest, around the block centre (routes arrive at cell centres): the level pad and visible hollow reach of its
     * nearest side. A round bed keeps NestResting.CUSHION; one flattened side bounds the square to LEVEL_FLAT, which an adult
     * (12.8) still fits, also between two walls of a one-block alcove; larger bodies (from about 1.15x) find no place.
     */
    public static NestResting.Bed bed(BlockState state) { return BEDS[walls(state) == 0 ? 0 : 1]; }
    private static final NestResting.Bed[] BEDS = {NestResting.CUSHION, new NestResting.Bed(LEVEL_FLAT, HOLLOW_FLAT, HOLLOW_FLAT, true, false)};
    /** How far the level pad reaches from the block centre toward the entrance (the doorway's inner edge). */
    public static double entranceReach(BlockState state) { return state.getValue(side(state.getValue(FACING))) ? LEVEL_FLAT : LEVEL_ROUND; }
}
