package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The pad, its approach stand, and the ground below the branch. */
record CanopySite(BlockPos cell, Vec3 pad, Vec3 stand, int height, double cover) {
    static final int MIN_HEIGHT = 4, MAX_HEIGHT = 12;

    static boolean tree(BlockState block) { return block.is(BlockTags.LOGS) || block.is(BlockTags.LEAVES); }

    static CanopySite inspect(ServerLevel level, YukkuriEntity body, BlockPos cell) {
        if (!level.hasChunksAt(cell.offset(-2, 0, -2), cell.offset(2, 0, 2))
                || !level.getWorldBorder().isWithinBounds(cell)) return null;
        var state = level.getBlockState(cell);
        var below = cell.below();
        var support = level.getBlockState(below);
        if (!(buildable(state) || state.is(ModBlocks.PREDATOR_NEST.get())) || !tree(support)
                || !support.isFaceSturdy(level, below, Direction.UP) || !body.life().covered(cell.above())) return null;
        int height = cell.getY() - groundY(level, cell);
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) return null;
        // Use the complete nest's actual flat top to validate both the eventual pad and its headroom.
        double top = ModBlocks.PREDATOR_NEST.get().defaultBlockState().getCollisionShape(level, cell).max(Direction.Axis.Y);
        var pad = Vec3.atBottomCenterOf(cell).add(0, top, 0);
        var existingFloor = state.isAir() ? Vec3.atBottomCenterOf(cell) : Vec3.atBottomCenterOf(cell)
                .add(0, state.getCollisionShape(level, cell).max(Direction.Axis.Y), 0);
        if (!clearBody(level, body, pad) || !SurfaceApproach.supported(body, existingFloor)) return null;
        var stand = body.life().homeEntrance(cell);
        if (stand == null || stand.distanceToSqr(Vec3.atBottomCenterOf(cell)) > 1
                || !SurfaceApproach.supported(body, stand) || !visibleFromGround(level, body, cell, pad)) return null;
        int covered = 0;
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            if (tree(level.getBlockState(cell.offset(x, 1, z)))) covered++;
            if ((x != 0 || z != 0) && tree(level.getBlockState(cell.offset(x, 0, z)))) covered++;
        }
        return new CanopySite(cell.immutable(), pad, stand, height, covered / 17.0);
    }

    boolean reachable(YukkuriEntity body) {
        return body.predator().canFlyToFeet(stand);
    }

    static boolean buildable(BlockState state) { return state.isAir() || state.is(ModBlocks.PREDATOR_NEST_FRAME.get()); }

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

    private static boolean visibleFromGround(ServerLevel level, YukkuriEntity body, BlockPos cell, Vec3 pad) {
        for (var side : Direction.Plane.HORIZONTAL) {
            var column = cell.relative(side, 6);
            if (!level.hasChunkAt(column)) continue;
            int ground = groundY(level, column);
            if (ground < cell.getY() && cell.getY() - ground <= MAX_HEIGHT && level.clip(new ClipContext(pad.add(0, .25, 0),
                    Vec3.atBottomCenterOf(column.atY(ground)).add(0, 1.5, 0), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, body)).getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}
