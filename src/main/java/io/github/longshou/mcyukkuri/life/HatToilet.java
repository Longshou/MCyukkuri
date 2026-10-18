package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Delegated life state; persistence stays in the original YukkuriLife compound. */
final class HatToilet {
    private final YukkuriLife o;
    HatToilet(YukkuriLife o) { this.o = o; }

    private int hatToiletTicks, hatToiletWait;
    private long hatToiletRetryAt;
    boolean completeAssistedFirstToilet(YukkuriEntity parent) {
        if (!(o.entity().level() instanceof ServerLevel) || !o.entity().childhood().assistedReleaseReady(parent)
                || o.bladder() < 10 || !o.entity().isAlive() || o.entity().isPanicking() || o.entity().isOnFire()
                || o.entity().isInWaterOrBubble() || o.isSleeping() || o.mealTicks() > 0) return false;
        Vec3 rear = o.entity().getVehicle() instanceof HatBoatEntity boat
                ? ToiletContact.hatOutlet(o.entity(), boat)
                : ToiletContact.anus(o.entity(), 0);
        if (rear == null) return false;
        if (!ToiletContact.drop(o.entity(), rear)) return false;
        o.emptyBladder();
        o.entity().showReaction("happy", "toilet_done", 60);
        return true;
    }

    boolean hatToiletReleasing() { return hatToiletTicks > 0; }

    boolean toiletInHat(HatBoatEntity boat) {
        if (o.entity().level().getGameTime() < hatToiletRetryAt) return false;
        if (!boat.protects(o.entity()) || !o.entity().childhood().firstToiletDone() || o.bladder() < 40 || o.constipated()
                || o.entity().isPanicking() || o.entity().isOnFire() || o.isSleeping() || o.mealTicks() > 0
                || o.entity().isCommandPlaying() || o.entity().operations().active()) {
            hatToiletTicks = hatToiletWait = 0; boat.releaseToiletSeat(o.entity()); return false;
        }
        if (++hatToiletWait > 300) {
            hatToiletTicks = hatToiletWait = 0; hatToiletRetryAt = o.entity().level().getGameTime() + 100;
            boat.releaseToiletSeat(o.entity()); return false;
        }
        if (!boat.prepareToiletSeat(o.entity())) { hatToiletTicks = hatToiletWait = 0; return false; }
        if (!boat.toiletSeatReady(o.entity())) { hatToiletTicks = 0; return true; }
        boat.faceToiletOutward(o.entity());
        Vec3 rear = ToiletContact.hatOutlet(o.entity(), boat);
        if (rear == null) { hatToiletTicks = 0; return true; }
        if (++hatToiletTicks < ToiletContact.RELEASE_TICKS) return true;
        if (ToiletContact.drop(o.entity(), rear)) { o.relieveBladder(40); o.entity().showReaction("happy", "toilet_done", 60); }
        hatToiletTicks = hatToiletWait = 0;
        boat.releaseToiletSeat(o.entity());
        return true;
    }
    void interrupt() { hatToiletTicks = hatToiletWait = 0; }
    void load() { interrupt(); hatToiletRetryAt = 0; }
}
