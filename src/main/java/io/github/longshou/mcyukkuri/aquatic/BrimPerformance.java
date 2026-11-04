package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.entity.ai.goal.Goal;

/** The brim's fall check, including interrupted play and a rescued child placed there. A brim is a legal place to be: a young
 * plays there and goes in for fright or a gasp ({@link PlayAboard}), so nothing here waits for or reserves a seat.
 * No selector flags: another goal ending play cannot remove the brim's fall check. */
final class BrimPerformance extends Goal {
    private final YukkuriEntity body;

    BrimPerformance(YukkuriEntity body) { this.body = body; }

    private static boolean aboard(YukkuriEntity body) {
        return !body.family().adult() && body.getVehicle() instanceof PlacedHatEntity hat
                && AquaticLife.afloat(hat) && PlayAboard.onBrim(body, hat);
    }

    @Override public boolean canUse() { return body.isAlive() && aboard(body); }
    @Override public boolean canContinueToUse() {
        return body.isAlive() && (aboard(body) || body.getVehicle() instanceof PlacedHatEntity
                && body.motor().current() instanceof Hop hop && !hop.interruptible());
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void tick() {
        if (fall()) {
            body.motor().interrupt("brim_hard_rock");
            stop();
        }
    }

    private boolean fall() {
        if (!(body.getVehicle() instanceof PlacedHatEntity hat) || !(hat.behavior() instanceof HullAfloat hull)
                || !hull.brimFall() || !PlayAboard.onBrim(body, hat)) return false;
        var outward = body.position().subtract(hat.position()).multiply(1, 0, 1).normalize();
        Motor.dismount(body, body.position(), hat.getDeltaMovement().add(outward.scale(HullPresentation.number("throw_horizontal"))), "brim_hard_rock");
        hull.playEvent("fall_ins");
        GameplayLog.event(body, "aquatic", "play fall_in cause=hard_rock hat={}", hat.getUUID());
        return true;
    }
}
