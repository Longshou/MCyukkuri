package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PredatorNestFrameBlock extends Block implements EntityBlock {
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

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new FrameEntity(pos, state); }

    public static final class FrameEntity extends BlockEntity {
        private UUID founder;

        public FrameEntity(BlockPos pos, BlockState state) { super(ModBlocks.PREDATOR_NEST_FRAME_ENTITY.get(), pos, state); }

        UUID founder() { return founder; }
        void setFounder(UUID founder) { this.founder = founder; setChanged(); }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (founder != null) tag.putUUID("Founder", founder);
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            founder = tag.hasUUID("Founder") ? tag.getUUID("Founder") : null;
        }
    }
}
