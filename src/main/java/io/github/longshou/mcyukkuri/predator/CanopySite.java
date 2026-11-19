package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The pad, its approach stand, and the ground below the branch. */
record CanopySite(BlockPos cell, Vec3 pad, Vec3 stand, int height, double cover, List<BlockPos> clearing) {
    static final int MIN_HEIGHT = 4, MAX_HEIGHT = 12;

    static boolean tree(BlockState block) { return block.is(BlockTags.LOGS) || block.is(BlockTags.LEAVES); }

    static boolean leafTop(ServerLevel level, BlockPos pos, BlockState state) {
        // Vanilla leaves reject mounted blocks, but their full collision top still carries a sole.
        return state.is(BlockTags.LEAVES) && Block.isFaceFull(state.getCollisionShape(level, pos), Direction.UP);
    }

    static CanopySite inspect(ServerLevel level, YukkuriEntity body, BlockPos cell) {
        return inspect(level, body, cell, false);
    }

    static CanopySite inspect(ServerLevel level, YukkuriEntity body, BlockPos cell, boolean initialSpawn) {
        if (!level.hasChunksAt(cell.offset(-2, 0, -2), cell.offset(2, 0, 2))
                || !level.getWorldBorder().isWithinBounds(cell)) return null;
        var state = level.getBlockState(cell);
        var below = cell.below();
        var support = level.getBlockState(below);
        if (!(buildable(state) || state.is(ModBlocks.PREDATOR_NEST.get()) || initialSpawn && clearable(state))
                || !tree(support) || !support.getFluidState().isEmpty()
                || !(support.isFaceSturdy(level, below, Direction.UP) || leafTop(level, below, support))) return null;
        int height = cell.getY() - groundY(level, cell);
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) return null;
        // Use the complete nest's actual flat top to validate both the eventual pad and its headroom.
        double top = ModBlocks.PREDATOR_NEST.get().defaultBlockState().getCollisionShape(level, cell).max(Direction.Axis.Y);
        var pad = Vec3.atBottomCenterOf(cell).add(0, top, 0);
        var clearing = new ArrayList<BlockPos>(2);
        if (initialSpawn && clearable(state)) clearing.add(cell.immutable());
        var above = cell.above();
        var box = body.getBoundingBox().move(pad.subtract(body.position()));
        if (initialSpawn && box.maxY > above.getY() && clearable(level.getBlockState(above))) clearing.add(above);
        if (!body.life().covered(clearing.contains(above) ? above.above() : above)) return null;
        if (clearing.isEmpty()) {
            var existingFloor = state.isAir() ? Vec3.atBottomCenterOf(cell) : Vec3.atBottomCenterOf(cell)
                    .add(0, state.getCollisionShape(level, cell).max(Direction.Axis.Y), 0);
            if (!clearBody(level, body, pad) || !SurfaceApproach.supported(body, existingFloor)) return null;
        } else if (body.getBbWidth() > 1 || box.maxY > above.above().getY()
                || box.maxY > above.getY() && !level.getBlockState(above).isAir() && !clearing.contains(above)) {
            // The projected body fits inside the open cell(s). Recheck collision and sole support before committing.
            return null;
        }
        var stand = body.life().homeEntrance(cell);
        if (stand == null || stand.distanceToSqr(Vec3.atBottomCenterOf(cell)) > 1
                || !SurfaceApproach.supported(body, stand) || !visibleFromGround(level, body, cell, pad, clearing)) return null;
        int covered = 0;
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            if (tree(level.getBlockState(cell.offset(x, 1, z))) && !clearing.contains(cell.offset(x, 1, z))) covered++;
            if ((x != 0 || z != 0) && tree(level.getBlockState(cell.offset(x, 0, z)))) covered++;
        }
        return new CanopySite(cell.immutable(), pad, stand, height, covered / 17.0, List.copyOf(clearing));
    }

    boolean reachable(YukkuriEntity body) {
        return body.predator().canFlyToFeet(stand);
    }

    static boolean buildable(BlockState state) { return state.isAir() || state.is(ModBlocks.PREDATOR_NEST_FRAME.get()); }

    /** A roof-sheltered founder can finish an existing frame and keeps its roof until the finished nest is claimed. */
    static boolean canBuild(YukkuriEntity body, BlockState state) {
        return buildable(state) && (!state.isAir() || body.life().home() == null);
    }

    private static boolean clearable(BlockState state) {
        return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT)
                && !state.getValue(LeavesBlock.PERSISTENT) && state.getFluidState().isEmpty();
    }

    static boolean clearBody(ServerLevel level, YukkuriEntity body, Vec3 feet) {
        return level.noCollision(body, body.getBoundingBox().move(feet.subtract(body.position())));
    }

    /** Heightmap finds exposed terrain, but a branch of logs also counts. Look below the tree in that column. */
    static int groundY(ServerLevel level, BlockPos from) {
        int top = Math.min(from.getY(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, from.getX(), from.getZ()));
        var cursor = new BlockPos.MutableBlockPos();
        for (int y = top - 1; y >= Math.max(level.getMinBuildHeight(), from.getY() - MAX_HEIGHT - 1); y--) {
            var state = level.getBlockState(cursor.set(from.getX(), y, from.getZ()));
            if (state.blocksMotion() && !tree(state)) return y + 1;
        }
        return from.getY() - MAX_HEIGHT - 1;
    }

    private static boolean visibleFromGround(ServerLevel level, YukkuriEntity body, BlockPos cell, Vec3 pad, List<BlockPos> clearing) {
        for (var side : Direction.Plane.HORIZONTAL) {
            var column = cell.relative(side, 6);
            if (!level.hasChunkAt(column)) continue;
            int ground = groundY(level, column);
            if (ground >= cell.getY() || cell.getY() - ground > MAX_HEIGHT) continue;
            double edge = SurfaceApproach.supportRadius(body);
            var start = pad.add(side.getStepX() * edge, body.getBbHeight() * .8, side.getStepZ() * edge);
            var end = Vec3.atBottomCenterOf(column.atY(ground)).add(0, 1.5, 0);
            if (clearing.contains(BlockPos.containing(start))) {
                // Only the prospective opening is omitted from the ray. Solid support below it still occludes the pad.
                start = start.lerp(end, (.501 - edge) / (6 - edge));
                if (start.y < cell.getY()) continue;
            }
            if (level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body))
                    .getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}
