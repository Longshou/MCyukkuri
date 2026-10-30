package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real hot-floor damage starts a short, collision-respecting escape, never teleportation. */
public final class HotFloorEscape {
    private final YukkuriEntity entity;
    private long escapeUntil;
    private Vec3 destination;
    private double floorY;

    HotFloorEscape(YukkuriEntity entity) { this.entity = entity; }
    public boolean active() { return entity.level().getGameTime() < escapeUntil && entity.isAlive() && !entity.isPassenger(); }

    public void burned() {
        long now = entity.level().getGameTime();
        escapeUntil = now + 160;
        floorY = entity.getY();
        destination = findExit();
        var velocity = entity.getDeltaMovement();
        var heading = destination == null ? Vec3.ZERO : destination.subtract(entity.position()).multiply(1, 0, 1).normalize().scale(.30);
        // Vanilla gravity/drag: .475 rises about half as high as the previous .70 impulse.
        Motor.velocity(entity, new Vec3(heading.x, Math.max(.475, velocity.y), heading.z), "hot_floor_jump");
        GameplayLog.event(entity, "body", "hot floor jump escape={} verticalSpeed={} health={}", destination, entity.getDeltaMovement().y, entity.getHealth());
    }

    private boolean hot(Vec3 at) {
        var feet = entity.getBoundingBox().move(at.subtract(entity.position())).deflate(.02);
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(feet.minX, at.y-.1, feet.minZ),
                BlockPos.containing(feet.maxX, at.y-.1, feet.maxZ)))
            if (entity.level().getBlockState(pos).is(Blocks.MAGMA_BLOCK)) return true;
        return false;
    }

    private Vec3 findExit() {
        Vec3 best = null;
        double distance = Double.MAX_VALUE;
        var origin = BlockPos.containing(entity.getX(), floorY, entity.getZ());
        for (var pos : BlockPos.betweenClosed(origin.offset(-8, 0, -8), origin.offset(8, 0, 8))) {
            var at = new Vec3(pos.getX()+.5, floorY, pos.getZ()+.5);
            double d = entity.position().distanceToSqr(at);
            if (d >= distance || hot(at) || !clearGroundRoute(at)) continue;
            best = at; distance = d;
        }
        return best;
    }

    private boolean clearGroundRoute(Vec3 at) {
        var start = new Vec3(entity.getX(), floorY, entity.getZ());
        int steps = Math.max(1, (int)Math.ceil(start.distanceTo(at)*4));
        for (int i=0; i<=steps; i++) if (!SurfaceApproach.supported(entity, start.lerp(at, (double)i/steps))) return false;
        return true;
    }

    public void tick() {
        if (entity.onGround() && !hot(entity.position())) {
            GameplayLog.event(entity, "body", "hot floor escaped health={} destination={}", entity.getHealth(), destination);
            escapeUntil = 0; return;
        }
        if (destination == null || !clearGroundRoute(destination)) {
            destination = null;
            if (entity.tickCount % 20 == 0) destination = findExit();
            if (destination == null) {
                GameplayLog.limited(entity, "body", "hot_floor_blocked", 100, "hot floor escape blocked; no clear supported exit health={}", entity.getHealth());
                return;
            }
        }
        // Continuous steering is essential while airborne; landing and walls retain vanilla collision.
        entity.getNavigation().stop();
        entity.getLookControl().setLookAt(destination.x, destination.y, destination.z);
        entity.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z, 1.8);
        var heading = destination.subtract(entity.position()).multiply(1, 0, 1);
        if (heading.lengthSqr() > .01) {
            heading = heading.normalize().scale(.30);
            Motor.velocity(entity, new Vec3(heading.x, entity.getDeltaMovement().y, heading.z), "hot_floor_steer");
        }
    }
}
