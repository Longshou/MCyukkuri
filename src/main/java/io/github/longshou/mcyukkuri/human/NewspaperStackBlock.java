package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One to three bundles of old newspapers (art/props/newspaper-stack), 4 folded sheets per bundle. Using the item on an intact stack
 * adds a bundle up to three; strays can tug single sheets from the top bundle (`taken` 0..3), rolling down a bundle every 4 sheets.
 */
public final class NewspaperStackBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty BUNDLES = IntegerProperty.create("bundles", 1, 3);
    public static final IntegerProperty TAKEN = IntegerProperty.create("taken", 0, 3);
    public static final int SHEETS_PER_BUNDLE = 4;
    // One box around the remaining sheets (1 px per sheet, 4 per bundle), the same at every facing; see art/props/newspaper-stack/build.py.
    private static final VoxelShape[] SHAPES = {
        box(3, 0, 3, 13, 1, 13), box(3, 0, 3, 13, 2, 13), box(3, 0, 3, 13, 3, 13), box(3, 0, 3, 13, 4, 13),
        box(3, 0, 3, 13, 5, 13), box(3, 0, 3, 13, 6, 13), box(3, 0, 3, 13, 7, 13), box(3, 0, 3, 13, 8, 13),
        box(3, 0, 3, 13, 9, 13), box(3, 0, 3, 13, 10, 13), box(3, 0, 3, 13, 11, 13), box(3, 0, 3, 13, 12, 13)
    };

    public NewspaperStackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(BUNDLES, 1).setValue(TAKEN, 0));
    }

    public static int sheets(BlockState state) {
        return state.getBlock() instanceof NewspaperStackBlock
                ? state.getValue(BUNDLES) * SHEETS_PER_BUNDLE - state.getValue(TAKEN) : 0;
    }

    public static ItemStack takeSheet(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof NewspaperStackBlock)) return ItemStack.EMPTY;
        int bundles = state.getValue(BUNDLES);
        int taken = state.getValue(TAKEN);
        int remainingBefore = bundles * SHEETS_PER_BUNDLE - taken;
        boolean changed;
        if (taken + 1 < SHEETS_PER_BUNDLE) {
            changed = level.setBlock(pos, state.setValue(TAKEN, taken + 1), Block.UPDATE_ALL);
        } else if (bundles > 1) {
            changed = level.setBlock(pos, state.setValue(BUNDLES, bundles - 1).setValue(TAKEN, 0), Block.UPDATE_ALL);
        } else {
            changed = level.removeBlock(pos, false);
        }
        if (!changed) return ItemStack.EMPTY;
        Vec3 top = Vec3.atBottomCenterOf(pos).add(0.0, remainingBefore / 16.0, 0.0);
        level.playSound(null, top.x, top.y, top.z, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.65F,
                0.9F + level.random.nextFloat() * 0.2F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), top.x, top.y, top.z,
                5, 0.16, 0.04, 0.16, 0.02);
        return new ItemStack(Items.PAPER);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, BUNDLES, TAKEN);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState here = context.getLevel().getBlockState(context.getClickedPos());
        if (here.is(this) && here.getValue(TAKEN) == 0 && here.getValue(BUNDLES) < 3) {
            return here.setValue(BUNDLES, here.getValue(BUNDLES) + 1);
        }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !context.isSecondaryUseActive() && context.getItemInHand().is(asItem())
                && state.getValue(TAKEN) == 0 && state.getValue(BUNDLES) < 3
                || super.canBeReplaced(state, context);
    }

    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canSupportCenter(level, pos.below(), Direction.UP);
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                               BlockPos pos, BlockPos neighborPos) {
        return direction == Direction.DOWN && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[Math.max(0, sheets(state) - 1)];
    }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
}
