package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A shallow wooden tray for things laid out on a desk, a table or a counter (art/structures/human-world/author_furniture.py). It is
 * a produce crate in every other respect: the same contents, hand interaction and drops, drawn lying flat by
 * client/ProduceCrateRenderer. RAISED is the counter-top form: the tray on a slab of its wood, standing in for a counter's slab.
 */
public final class DisplayTrayBlock extends ProduceCrateBlock {
    public static final BooleanProperty RAISED = BooleanProperty.create("raised");
    /** Tray floor above the block's base, in pixels: where the goods lie. */
    public static final int FLOOR = 1, SLAB = 8;
    private static final VoxelShape LOW = Block.box(1, 0, 1, 15, 2, 15);
    private static final VoxelShape HIGH = Shapes.or(Block.box(0, 0, 0, 16, SLAB, 16), Block.box(1, SLAB, 1, 15, SLAB + 2, 15));
    private static final VoxelShape HIGH_COLLISION = Block.box(0, 0, 0, 16, SLAB, 16);   // exactly the slab it replaces

    public DisplayTrayBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(RAISED, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(RAISED);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return super.getStateForPlacement(context).setValue(RAISED, false); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(RAISED) ? HIGH : LOW;
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(RAISED) ? HIGH_COLLISION : LOW;
    }
}
