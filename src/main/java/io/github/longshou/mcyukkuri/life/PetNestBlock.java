package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
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
 * the entrance (the low lip): the first side from the placer clockwise that is not flattened and has walking room in the cell
 * outside it, and where a standard adult standing there is supported as it is for any walker (NestDoorway.supportsAdult);
 * with none the bed is not placed. A diagonal block flattens the corner's other side(s) so nothing enters the
 * diagonal cell. Later block changes refit it the same way (entrance turned if its side closes); enclosed it keeps its entrance.
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
    private static boolean blocks(BlockGetter level, BlockPos pos, Direction side) {
        var next = pos.relative(side);
        double near = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0 : 1 - OVERHANG;
        return intrudes(level, next, side.getAxis() == Direction.Axis.X ? Shapes.box(near, .125, 0, near + OVERHANG, TOP, 1)
                : Shapes.box(0, .125, near, 1, TOP, near + OVERHANG));
    }
    /** Whether the diagonal neighbour between sides {@code a} and {@code b} reaches into the corner the round roll overhangs. */
    private static boolean blocksCorner(BlockGetter level, BlockPos pos, Direction a, Direction b) {
        var cell = pos.relative(a).relative(b);
        int sx = a.getStepX() + b.getStepX(), sz = a.getStepZ() + b.getStepZ();
        double x = sx > 0 ? 0 : 1 - OVERHANG, z = sz > 0 ? 0 : 1 - OVERHANG;
        return intrudes(level, cell, Shapes.box(x, .125, z, x + OVERHANG, TOP, z + OVERHANG));
    }
    private static boolean intrudes(BlockGetter level, BlockPos cell, VoxelShape band) {
        var state = level.getBlockState(cell);
        // Each bed owns its cell. Its transient fit must not change how a neighbouring bed fits.
        if (state.getBlock() instanceof PetNestBlock || state.getBlock() instanceof LargeNestBlock) return true;
        var shape = state.getCollisionShape(level, cell);
        return !shape.isEmpty() && Shapes.joinIsNotEmpty(shape, band, BooleanOp.AND);
    }
    /** Whether a body can stand in the cell outside that side (nothing above floor coverings there; the walking clearance of
     *  the bodies a bed holds, at most one block tall). Separate from the mesh test above. */
    private static boolean walkable(BlockGetter level, BlockPos pos, Direction side) {
        var cell = pos.relative(side);
        var shape = level.getBlockState(cell).getCollisionShape(level, cell);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= .125;
    }

    /**
     * The bed fitted to its eight neighbours: sides whose cardinal neighbour reaches into the overhang are flattened; the
     * entrance is the first side, from {@code preferred} clockwise, that a body can walk into, that is not flattened (a
     * flattened lip is too narrow to pass) and where the standard adult standing outside really is supported; then a diagonal
     * neighbour reaching into a corner flattens that corner's sides except the entrance, so the round roll never enters the
     * diagonal cell. Null when no side can be the entrance.
     */
    private static BlockState fitted(BlockState state, LevelReader level, BlockPos pos, Direction preferred) {
        for (Direction side : Direction.Plane.HORIZONTAL) state = state.setValue(side(side), blocks(level, pos, side));
        int corners = 0;
        for (Direction side : Direction.Plane.HORIZONTAL)
            if (blocksCorner(level, pos, side, side.getClockWise())) corners |= 1 << side.get2DDataValue();
        Direction side = preferred;
        for (int k = 0; k < 4; k++, side = side.getClockWise()) {
            if (state.getValue(side(side)) || !walkable(level, pos, side)) continue;
            var candidate = fitCorners(state.setValue(FACING, side), side, corners);
            if (NestDoorway.supportsAdult(level, pos, candidate)) return candidate;
        }
        return null;
    }

    private static BlockState fitCorners(BlockState state, Direction entrance, int corners) {
        for (Direction a : Direction.Plane.HORIZONTAL) {
            var b = a.getClockWise();
            if (state.getValue(side(a)) || state.getValue(side(b)) || (corners & (1 << a.get2DDataValue())) == 0) continue;
            if (a != entrance) state = state.setValue(side(a), true);
            if (b != entrance) state = state.setValue(side(b), true);
        }
        return state;
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel(); var pos = context.getClickedPos();
        var state = fitted(defaultBlockState(), level, pos, context.getHorizontalDirection().getOpposite());
        if (state == null && level.isClientSide && context.getPlayer() != null)
            context.getPlayer().displayClientMessage(Component.literal("窝没有入口：至少留出一侧有承托的空格"), true);
        return state;
    }
    /** Schedule one refit for a cardinal change, just like the diagonal notification. */
    @Override protected BlockState updateShape(BlockState state, Direction side, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        if (side.getAxis().isHorizontal() && !level.isClientSide()) level.scheduleTick(pos, this, 1);
        return super.updateShape(state, side, neighbour, level, pos, neighbourPos);
    }
    private static BlockState refit(BlockState state, LevelReader level, BlockPos pos) {
        var fitted = fitted(state, level, pos, state.getValue(FACING));
        if (fitted != null) return fitted;
        for (Direction side : Direction.Plane.HORIZONTAL)
            state = state.setValue(side(side), blocks(level, pos, side) || side != state.getValue(FACING)
                    && (blocksCorner(level, pos, side, side.getClockWise()) || blocksCorner(level, pos, side, side.getCounterClockWise())));
        return state;
    }
    /** Scheduled when its block entity loads (an older save had no fit) or a diagonal neighbour changed. */
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.isAreaLoaded(pos, 1)) return;
        var fitted = refit(state, level, pos);
        if (fitted.equals(state)) return;
        level.setBlock(pos, fitted, UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE);
        if (fitted.getValue(FACING) != state.getValue(FACING))
            GameplayLog.world(level, "home", "nest entrance turned nest={} from={} to={}", pos.toShortString(),
                    state.getValue(FACING).getName(), fitted.getValue(FACING).getName());
    }
    /** A block changed at {@code changed}: beds diagonal to it refit on the next tick (cardinal ones get updateShape). */
    public static void nearbyChanged(LevelAccessor level, BlockPos changed) {
        for (Direction a : Direction.Plane.HORIZONTAL) {
            var at = changed.relative(a).relative(a.getClockWise());
            if (level.isClientSide() || !level.hasChunkAt(at)) continue;
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof PetNestBlock bed) level.scheduleTick(at, bed, 1);
        }
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
