package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Navigation retains its target and collision handling while the contact gait pauses. */
public final class HouseCrawlControl extends MoveControl {
    private final YukkuriEntity entity;

    public HouseCrawlControl(YukkuriEntity entity) {
        super(entity);
        this.entity = entity;
    }

    @Override public void tick() {
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
        entity.syncCrawl(crawl);
        if (crawl) entity.yBodyRot = entity.getYRot();
    }
}
