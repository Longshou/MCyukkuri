package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** House-only routing: vanilla paths outside, checked contact alignment on a flat floor. */
final class HouseNavigation {
    private final YukkuriEntity entity;
    private Vec3 target, progressPosition;
    private int nextPathTick, progressTick, blockedWindows, failedPaths, nextClearTick;
    private boolean clear;

    HouseNavigation(YukkuriEntity entity) { this.entity = entity; }

    /** False means give this attempt up; the caller keeps any real carried inventory. */
    boolean moveTo(Vec3 point, double speed) {
        int now = entity.tickCount;
        if (target == null || target.distanceToSqr(point) > .0025) {
            target = point; progressPosition = entity.position(); progressTick = now;
            nextPathTick = nextClearTick = now; blockedWindows = failedPaths = 0; clear = false;
        }
        if (now - progressTick >= 40) {
            if (entity.position().distanceToSqr(progressPosition) < .0004) {
                blockedWindows++; nextPathTick = nextClearTick = now;
            } else blockedWindows = 0;
            progressPosition = entity.position(); progressTick = now;
        }
        if (blockedWindows >= 4 || failedPaths >= 3) { stop(); return false; }

        // Distance alone is never permission to cut through the last wall or a table leg.
        if (now >= nextClearTick) {
            clear = entity.distanceToSqr(point) <= 2.25 && clearSegment(point);
            nextClearTick = now + 10;
        }
        if (clear && clearNextStep(point)) {
            entity.getNavigation().stop();
            entity.getMoveControl().setWantedPosition(point.x, point.y, point.z, speed);
            failedPaths = 0;
            return true;
        }
        clear = false;
        if (now >= nextPathTick) {
            nextPathTick = now + 20;
            if (entity.life().navigate(BlockPos.containing(point), speed, false)) failedPaths = 0;
            else { failedPaths++; halt(); }
        }
        return true;
    }

    private boolean clearNextStep(Vec3 point) {
        var delta = point.subtract(entity.position());
        double length = delta.length();
        return length < 1e-6 || clearSegment(entity.position().add(delta.scale(Math.min(.08, length) / length)));
    }

    /** Sample the whole body, including support and fluids, not just a ray from its eyes. */
    private boolean clearSegment(Vec3 point) {
        var from = entity.position();
        var delta = point.subtract(from);
        if (Math.abs(delta.y) > .12) return false; // Steps and height changes belong to navigation.
        int samples = Math.max(1, (int)Math.ceil(delta.length() / .08));
        for (int i = 1; i <= samples; i++) {
            var sample = from.add(delta.scale((double)i / samples));
            var pos = BlockPos.containing(sample);
            var floor = BlockPos.containing(sample.x, sample.y - .05, sample.z);
            // The bedding is a destination, never a short cut: only its own cell may be entered, and only by a path that ends or starts in it.
            if (entity.level().getBlockState(pos).getBlock() instanceof HouseNestBlock
                    && !pos.equals(BlockPos.containing(point)) && !pos.equals(BlockPos.containing(from))) return false;
            if (!entity.level().hasChunkAt(pos) || !entity.level().hasChunkAt(floor)
                    || !entity.level().getFluidState(pos).isEmpty()
                    || !entity.level().getFluidState(floor).isEmpty()
                    || !entity.level().getBlockState(floor).isFaceSturdy(entity.level(), floor, Direction.UP)
                    || !entity.level().noCollision(entity, entity.getBoundingBox().move(sample.subtract(from)))) return false;
        }
        return true;
    }

    private void halt() {
        entity.getNavigation().stop();
        entity.getMoveControl().setWantedPosition(entity.getX(), entity.getY(), entity.getZ(), 0);
        entity.setSpeed(0);
        entity.syncCrawl(false);
    }

    void stop() {
        if (target != null) halt();
        target = progressPosition = null; clear = false;
    }
}
