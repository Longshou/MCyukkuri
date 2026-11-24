package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.UUID;

/** Immediate reasons to release a short action; ordinary choices wait for its existing completion point. */
public final class BehaviorContinuity {
    private BehaviorContinuity() {}

    public static boolean emergency(YukkuriEntity entity) {
        return entity.isCommandPlaying() || danger(entity);
    }

    /** Real danger or physical incapacitation, excluding ordinary command-played performance clips (such as a badge bow). */
    public static boolean danger(YukkuriEntity entity) {
        return entity.isPanicking() || entity.operations().controlling() || hazard(entity, null);
    }

    /**
     * Danger from the world rather than from a hand on the body: fire, water, dying, a hot floor, starvation or a threat in sight.
     * A threat that is {@code except} (the owner whose scold or blow the body is taking) is not a hazard.
     */
    public static boolean hazard(YukkuriEntity entity, UUID except) {
        if (!entity.isAlive() || entity.isRemoved() || entity.isOnFire()
                || entity.isInWaterOrBubble() || entity.waterDistress().active() && entity.waterDistress().phase() != WaterDistress.Phase.RECOVER
                || entity.body().dying() || entity.mind().nonYukkuri()
                || entity.life().hotFloor().active() || entity.life().nutrition() < 15 || entity.life().energy() < 15) return true;
        var threat = entity.status().reactions().threat().source();
        if (threat == null || !threat.isAlive() || threat.getUUID().equals(except)) return false;
        if (threat instanceof YukkuriEntity predator && predator.predator().prey() == entity) return true;
        double range = entity.life().personality().threatDistance(entity.life().personality().fear(threat.getUUID()));
        return entity.hasLineOfSight(threat) && entity.distanceToSqr(threat) <= range * range;
    }
}
