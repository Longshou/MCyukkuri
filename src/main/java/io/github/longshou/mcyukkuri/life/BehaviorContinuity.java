package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Immediate reasons to release a short action; ordinary choices wait for its existing completion point. */
public final class BehaviorContinuity {
    private BehaviorContinuity() {}

    public static boolean emergency(YukkuriEntity entity) {
        if (!entity.isAlive() || entity.isRemoved() || entity.isPanicking() || entity.isOnFire()
                || entity.isInWaterOrBubble() || entity.waterDistress().active()
                || entity.isCommandPlaying() || entity.operations().controlling()
                || entity.body().dying() || entity.mind().nonYukkuri()
                || entity.life().nutrition() < 15 || entity.life().energy() < 15) return true;
        var threat = entity.status().reactions().threat().source();
        if (threat == null || !threat.isAlive()) return false;
        if (threat instanceof YukkuriEntity predator && predator.predator().prey() == entity) return true;
        double range = entity.life().personality().threatDistance(entity.life().personality().fear(threat.getUUID()));
        return entity.hasLineOfSight(threat) && entity.distanceToSqr(threat) <= range * range;
    }
}
