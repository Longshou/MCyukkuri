package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Drive;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import net.minecraft.world.entity.ai.goal.Goal;

/** The brim's continuous performance, including interrupted play and a rescued child placed there.
 * No selector flags: another goal ending play cannot remove the brim's fall check or return. */
final class BrimPerformance extends Goal {
    private final YukkuriEntity body;
    private IntentRunner returning;

    BrimPerformance(YukkuriEntity body) { this.body = body; }

    static boolean aboard(YukkuriEntity body) {
        return !body.family().adult() && body.getVehicle() instanceof PlacedHatEntity hat
                && AquaticLife.afloat(hat) && PlayAboard.onBrim(body, hat);
    }

    @Override public boolean canUse() { return body.isAlive() && aboard(body); }
    @Override public boolean canContinueToUse() {
        return body.isAlive() && (aboard(body) || body.getVehicle() instanceof PlacedHatEntity
                && body.motor().current() instanceof Hop hop && !hop.interruptible());
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { returning = null; }

    @Override public void tick() {
        if (fall()) {
            body.motor().interrupt("brim_hard_rock");
            stop();
            return;
        }
        if (Drive.running(body, AquaticGoal.DOMAIN) instanceof PlayAboard play) {
            play.returnOnEnd(this::returnSeat);
        } else if (returning != null) {
            returning.tick();
        } else if (body.motor().owner() == null || !body.motor().owner().active()) {
            returnSeat();
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

    /** Called by play's end/stop path, so its return starts before another desire can take the body. */
    private void returnSeat() {
        if (!canUse() || returning != null) return;
        var hat = (PlacedHatEntity) body.getVehicle();
        long deadline = body.level().getGameTime() + (long) PlayAboard.number("play_deadline");
        Intent intent = new Intent() {
            @Override public String id() { return "return_aboard"; }
            @Override public long deadline() { return deadline; }
            @Override public boolean satisfied() { return body.getVehicle() == hat && !PlayAboard.onBrim(body, hat)
                    && !(body.motor().current() instanceof Hop); }
            @Override public List<Method> methods() {
                return List.of(Plan.of(id(), () -> body.getVehicle() != hat ? null : List.of(
                        () -> hat.steady() && hat.seatDestination(body) != null ? null
                                : new Step.Wait(() -> hat.steady() && hat.seatDestination(body) != null,
                                        (int) PlayAboard.number("play_deadline"), Behaviors.HAT_CRADLE),
                        () -> new Step.Do(new Hop(hat)))));
            }
        };
        returning = new IntentRunner(body, intent, "brim_performance");
        returning.start();
        returning.tick();
    }

    @Override public void stop() { if (returning != null) returning.stop("left_brim"); }
}
