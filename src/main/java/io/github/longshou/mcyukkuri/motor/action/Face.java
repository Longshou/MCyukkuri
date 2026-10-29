package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import java.util.Locale;
import net.minecraft.util.Mth;

/**
 * Turn on the spot until the body faces {@code yaw} (spec 9.4): at most the ability's turn rate per tick, through
 * {@link Motor#face}, so no caller keeps its own turn budget. The whole body turns and the action is done inside 3°.
 *
 * <p>Take-off/execution beats are physics' job: this action never moves, so it returns {@link TravelMode#CONSUMED} and the
 * watchdog sees only the remaining angle. Any owner may replace it, which is what makes it usable from a domain goal that
 * re-aims every tick.
 */
public final class Face implements Action {
    public static final String ID = "face";
    /** The remaining angle that counts as arrived (degrees); a caller re-aiming must use the same threshold. */
    public static final double FACE_ARRIVED = 3;

    private final float yaw;
    private double turnRate;
    private long startTick;

    public Face(float yaw) { this.yaw = yaw; }

    /** The yaw of this action, so a caller can tell whether the motor already turns where it wants. */
    public float target() { return yaw; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        turnRate = BodyAbility.of(body).turnRate();
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        turnRate = BodyAbility.of(body).turnRate();
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        double left = Mth.wrapDegrees(yaw - body.getYRot());
        if (Math.abs(left) <= FACE_ARRIVED)
            return new Outcome.Done(String.format(Locale.ROOT, "yaw=%.1f", body.getYRot()));
        double step = Math.min(Math.abs(left), turnRate);
        if (step > 0) Motor.face(body, body.getYRot() + (float) Math.copySign(step, left));
        // The remaining angle is the progress: a body whose turn rate is zero never reports progress and the watchdog ends it.
        return new Running(Math.abs(left), TravelMode.CONSUMED);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "to=%.1f turnRate=%.1f", yaw, turnRate);
    }
}
