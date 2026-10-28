package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import java.util.Locale;
import net.minecraft.util.Mth;

/**
 * Turn on the spot until the body faces {@code yaw} (spec 9.4): at most the ability's turn rate per tick, through
 * {@link Motor#face}, so no caller keeps its own turn budget. The whole body turns; the action is done inside 3°.
 */
public final class Face implements Action {
    public static final String ID = "face";
    /** The remaining angle that counts as arrived (degrees). */
    private static final double ARRIVED = 3;

    private final float yaw;
    private double turnRate;

    public Face(float yaw) { this.yaw = yaw; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        turnRate = BodyAbility.of(body).turnRate();
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        turnRate = BodyAbility.of(body).turnRate();
    }

    @Override public Progress tick(YukkuriEntity body) {
        double left = Mth.wrapDegrees(yaw - body.getYRot());
        if (Math.abs(left) <= ARRIVED) return new Outcome.Done("yaw=" + String.format(Locale.ROOT, "%.1f", body.getYRot()));
        double step = turnRate <= 0 ? Math.abs(left) : Math.min(Math.abs(left), turnRate);
        Motor.face(body, body.getYRot() + (float) Math.copySign(step, left));
        return new Running(Math.abs(left), TravelMode.CONSUMED);
    }

    @Override public boolean interruptible() { return true; }

    @Override public void abort(YukkuriEntity body, String why) {}

    @Override public MotionPose pose() { return MotionPose.NONE; }
}
