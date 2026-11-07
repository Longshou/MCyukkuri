package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Block reads for observations must never call ServerChunkCache.getChunk(create=true). */
final class TraceBlocks implements BlockGetter {
    private final ServerLevel level;
    private boolean missing;

    private TraceBlocks(ServerLevel level) { this.level = level; }

    /** Vanilla Entity.isInWall geometry, with unknown instead of loading a missing chunk. */
    static Boolean inWall(YukkuriEntity entity) {
        if (entity.noPhysics) return false;
        var blocks = new TraceBlocks((ServerLevel) entity.level());
        float width = entity.getBbWidth() * .8F;
        AABB box = AABB.ofSize(entity.getEyePosition(), width, 1.0E-6, width);
        boolean hit = BlockPos.betweenClosedStream(box).anyMatch(pos -> {
            BlockState state = blocks.getBlockState(pos);
            return !state.isAir() && state.isSuffocating(blocks, pos)
                    && Shapes.joinIsNotEmpty(state.getCollisionShape(blocks, pos)
                    .move(pos.getX(), pos.getY(), pos.getZ()), Shapes.create(box), BooleanOp.AND);
        });
        return blocks.missing ? null : hit;
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return Blocks.VOID_AIR.defaultBlockState();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) return chunk.getBlockState(pos);
        missing = true;
        return Blocks.AIR.defaultBlockState();
    }

    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) return chunk.getBlockEntities().get(pos);
        missing = true;
        return null;
    }
    @Override public int getHeight() { return level.getHeight(); }
    @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
}
