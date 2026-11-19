package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.life.NestSupport;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return NestSupport.below(level,pos);
    }
    @Override protected BlockState updateShape(BlockState state, Direction side, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if(side==Direction.DOWN && !canSurvive(state,level,pos))return Blocks.AIR.defaultBlockState();
        return super.updateShape(state,side,neighbour,level,pos,neighbourPos);
    }

    public static final class FrameEntity extends BlockEntity {
        private UUID founder;
        private long missingSince = -1;

        public FrameEntity(BlockPos pos, BlockState state) { super(ModBlocks.PREDATOR_NEST_FRAME_ENTITY.get(), pos, state); }

        UUID founder() { return founder; }
        void setFounder(UUID founder) { this.founder = founder; missingSince = -1; setChanged(); }
        void restoreFounder(FrameEntity previous) {
            founder = previous.founder;
            missingSince = previous.missingSince;
            setChanged();
        }
        void founderPresent() {
            if (missingSince >= 0) { missingSince = -1; setChanged(); }
        }

        boolean available(ServerLevel level) {
            if (founder == null) return true;
            for (var dimension : level.getServer().getAllLevels()) {
                var owner = dimension.getEntity(founder);
                if (owner != null) {
                    founderPresent();
                    return !owner.isAlive();
                }
            }
            // Give an unloaded founder one full game day from the first observed absence to return.
            if (missingSince < 0) { missingSince = level.getGameTime(); setChanged(); }
            return level.getGameTime() - missingSince >= 24000;
        }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (founder != null) tag.putUUID("Founder", founder);
            if (missingSince >= 0) tag.putLong("MissingSince", missingSince);
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            founder = tag.hasUUID("Founder") ? tag.getUUID("Founder") : null;
            missingSince = tag.contains("MissingSince") ? tag.getLong("MissingSince") : -1;
        }
    }
}
