package io.github.longshou.mcyukkuri.predator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PredatorNestFrameBlock extends Block {
    public static final int MAX_TWIGS = 7;
    public static final IntegerProperty TWIGS = IntegerProperty.create("twigs", 1, MAX_TWIGS);
    private static final VoxelShape FRAME = box(0, 0, 0, 16, 1.6, 16);

    public PredatorNestFrameBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TWIGS, 1));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TWIGS);
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FRAME;
    }
}
