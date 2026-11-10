package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Motor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;

/** Navigation retains its target and collision handling while the contact gait pauses. */
public final class HouseCrawlControl extends MoveControl {
    private final YukkuriEntity entity;

    public HouseCrawlControl(YukkuriEntity entity) {
        super(entity);
        this.entity = entity;
    }

    @Override public void tick() {
        // Direct goal movement uses this control too, after navigation has finished its last node.
        if (operation == Operation.MOVE_TO && entity.onGround() && !entity.isInWaterOrBubble()) {
            var dry = SurfaceApproach.dryAim(entity, new Vec3(wantedX, entity.getY(), wantedZ));
            wantedX = dry.x;
            wantedZ = dry.z;
        }
        boolean wanted = hasWanted();
        boolean moving = operation == Operation.MOVE_TO;
        double footTop = Double.NaN;
        boolean blockedByHigherTarget = false;
        if (moving) {
            BlockPos foot = entity.blockPosition();
            VoxelShape shape = entity.level().getBlockState(foot).getCollisionShape(entity.level(), foot);
            if (!shape.isEmpty()) footTop = shape.max(Direction.Axis.Y) + foot.getY();
            double dx = wantedX - entity.getX(), dz = wantedZ - entity.getZ();
            blockedByHigherTarget = wantedY - entity.getY() > entity.maxUpStep()
                    && dx * dx + dz * dz < Math.max(1.0F, entity.getBbWidth());
        }
        super.tick();
        // Low rims (nests) are crossed by ordinary step-up collision; vanilla would jump on every tick inside them.
        if (moving && operation == Operation.JUMPING && !Double.isNaN(footTop) && !blockedByHigherTarget) {
            double rise = footTop - entity.getY();
            if (rise > 0 && rise <= entity.maxUpStep()) {
                operation = Operation.WAIT;
                if (entity.getJumpControl() instanceof YukkuriJumpControl jump) jump.cancel();
            }
        }
        boolean crawl = wanted && operation != Operation.JUMPING && entity.onGround()
                && entity.canCrawlNow()
                && (!entity.childhood().canJump() || entity.body().footBurned() || entity.house().wantsCrawl() || entity.life().wantsCrawl());
        // On a host's surface the host walk owns the crawl (Walk); here only the ground gait.
        if (!entity.isPassenger()) entity.syncCrawl(crawl);
        if (crawl) Motor.face(entity, entity.getYRot());
    }

    @Override protected float rotlerp(float from, float to, float maxTurn) {
        return Motor.turnYaw(entity, to);
    }
}
