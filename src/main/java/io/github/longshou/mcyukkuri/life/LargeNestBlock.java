package io.github.longshou.mcyukkuri.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A family nest on a real 2x2 footprint, like a bed: the clicked cell is part 0 (north-west, owns the block entity and the
 * drop), part bit 1 is the +x cell and bit 2 the +z cell. Placement needs all four cells free; losing any part removes the
 * others. Each part collides with its own quarter of the nest shell, so nothing reaches outside the four cells.
 */
public final class LargeNestBlock extends CareBlock {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 3);
    private final VoxelShape[] collision = new VoxelShape[4], outline = new VoxelShape[4];

    public LargeNestBlock(Properties properties, String style) {
        super(Kind.NEST, properties, style, 6, 0);
        registerDefaultState(stateDefinition.any().setValue(PART, 0));
        boolean wild = style.equals("large_wild_nest");
        VoxelShape whole = wild ? wildShape() : authoredShape(style, 16);
        double top = wild ? 3 : authoredTop(style);
        for (int part = 0; part < 4; part++) {
            double dx = part & 1, dz = part >> 1;
            collision[part] = Shapes.join(whole.move(-dx, 0, -dz), Shapes.block(), BooleanOp.AND).optimize();
            outline[part] = box(0, 0, 0, 16, top, 16);
        }
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(PART); }
    /** Wild bedding (drafts/care-props/wild.py large_wild_nest): rounded 32-unit footprint centred on the shared corner, a 0.5 leaf
     *  floor, a 2.6 pressed-grass rim outside a 14.75 rounded square and a low southern (+z) entrance. */
    private static VoxelShape wildShape() {
        VoxelShape shape = Shapes.empty();
        double floor = .5, rim = 2.6;
        for (double x = -16; x < 16; x += 1) {
            double sample = Math.abs(x + .5);
            double outer = Math.pow(Math.max(0, Math.pow(16, 4) - Math.pow(sample, 4)), .25);
            double inner = Math.pow(Math.max(0, Math.pow(14.75, 4) - Math.pow(sample, 4)), .25);
            shape = Shapes.or(shape, box(16 + x, 0, 16 - outer, 17 + x, floor, 16 + outer));
            if (inner == 0) shape = Shapes.or(shape, box(16 + x, 0, 16 - outer, 17 + x, rim, 16 + outer));
            else {
                shape = Shapes.or(shape, box(16 + x, 0, 16 - outer, 17 + x, rim, 16 - inner));
                double south = sample < 7 ? floor : sample < 10 ? 1.5 : sample < 12 ? 2.3 : rim;
                shape = Shapes.or(shape, box(16 + x, 0, 16 + inner, 17 + x, south, 16 + outer));
            }
        }
        return shape.optimize();
    }
    public static BlockPos origin(BlockState state, BlockPos pos) {
        int part = state.getValue(PART);
        return pos.offset(-(part & 1), 0, -(part >> 1));
    }
    private static BlockPos at(BlockPos origin, int part) { return origin.offset(part & 1, 0, part >> 1); }
    @Override protected BlockPos owner(BlockState state, BlockPos pos) { return origin(state, pos); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return state.getValue(PART) == 0 ? super.newBlockEntity(pos, state) : null; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return outline[state.getValue(PART)]; }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return collision[state.getValue(PART)]; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel();
        for (int part = 1; part < 4; part++) {
            var cell = at(context.getClickedPos(), part);
            if (!level.getBlockState(cell).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(cell)) {
                if (level.isClientSide && context.getPlayer() != null)
                    context.getPlayer().displayClientMessage(Component.literal("大窝占 2×2 格：点击格的东侧、南侧和东南格须空出"), true);
                return null;
            }
        }
        return defaultBlockState();
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        for (int part = 1; part < 4; part++) level.setBlock(at(pos, part), state.setValue(PART, part), 3);
        level.blockUpdated(pos, Blocks.AIR);
        state.updateNeighbourShapes(level, pos, 3);
    }
    /** A part whose +x / +z partner (or -x / -z, by its place) is gone becomes air, so the whole nest goes together. */
    @Override protected BlockState updateShape(BlockState state, Direction side, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        int part = state.getValue(PART);
        Direction xPartner = (part & 1) == 0 ? Direction.EAST : Direction.WEST, zPartner = (part >> 1) == 0 ? Direction.SOUTH : Direction.NORTH;
        if (side == xPartner || side == zPartner) {
            int expected = part ^ (side == xPartner ? 1 : 2);
            return neighbour.is(this) && neighbour.getValue(PART) == expected ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, side, neighbour, level, pos, neighbourPos);
    }
    /** Creative breaking removes the other parts without a drop; in survival they go through updateShape and only part 0 drops. */
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()) {
            var origin = origin(state, pos);
            for (int part = 0; part < 4; part++) {
                var cell = at(origin, part);
                var other = level.getBlockState(cell);
                if (!cell.equals(pos) && other.is(this)) {
                    level.setBlock(cell, Blocks.AIR.defaultBlockState(), 35);
                    level.levelEvent(player, 2001, cell, Block.getId(other));
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
