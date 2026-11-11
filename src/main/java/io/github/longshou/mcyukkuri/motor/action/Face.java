package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import java.util.Locale;
import net.minecraft.util.Mth;

/** Turn on the spot to face {@code yaw}: at most the ability's turn rate a tick through {@link Motor#face}, so no caller keeps a turn budget, and any owner may replace it. */
public final class Face implements Action {
    public static final String ID = "face";
    /** The remaining angle that counts as arrived (degrees); a caller re-aiming must use the same threshold. */
    public static final double FACE_ARRIVED = 3;

    private final float yaw;
    private final double tolerance;
    private double turnRate;
    private long startTick;
    /** The stroke clip she turns the hull with this tick (Paddle.strokeClip), or null while she turns on her seat. */
    private String hullStroke;

    public Face(float yaw) { this(yaw, FACE_ARRIVED); }

    public Face(float yaw, double tolerance) {
        this.yaw = yaw;
        this.tolerance = tolerance;
    }

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

    /** Whether the turn to this yaw is admitted where the body stands (what {@link #tick} steps through, nothing
     *  written): on a host, the riders leave it room at the end of the turn, or the controlling captain's paddle can swing the hull. */
    public static boolean admitted(YukkuriEntity body, float yaw) {
        double left = Mth.wrapDegrees(yaw - body.yBodyRot);
        if (Math.abs(left) <= FACE_ARRIVED || BodyAbility.of(body).turnRate() <= 0 || !(Attach.of(body) instanceof AttachState.OnHost on)) return true;
        var host = on.host();
        return Floor.canTurn(host, body, (float) left)
                || host.entity().getControllingPassenger() == body && Paddle.toolCheck(body, host).ok();
    }

    @Override public MotionPose pose() {
        return hullStroke != null ? new MotionPose(hullStroke, startTick, true) : MotionPose.NONE;
    }
    @Override public Progress tick(YukkuriEntity body) {
        hullStroke = null;
        double left = Mth.wrapDegrees(yaw - body.yBodyRot);
        if (Math.abs(left) <= tolerance)
            return new Outcome.Done(String.format(Locale.ROOT, "yaw=%.1f", body.yBodyRot));
        double step = Math.min(Math.abs(left), turnRate);
        float turn = (float) Math.copySign(step, left);
        if (step > 0 && Attach.of(body) instanceof AttachState.OnHost on) {
            if (Floor.turn(on.host(), body, turn) == 0) {
                var host = on.host();
                if (host.entity().getControllingPassenger() != body || !Paddle.toolCheck(body, host).ok()
                        || !host.thrust(body, Vec3.ZERO, turn)) return Outcome.of(Reason.NO_ROOM, 0, "turn:riders");
                body.life().spendEnergy(Paddle.cost(body, host));
                hullStroke = Paddle.strokeClip(body, host);
                GameplayLog.limited(body, "hat", "turn_hull", 100, "turn via_hull body={} step={}", body.getUUID(), turn);
            }
        } else if (step > 0) Motor.face(body, body.yBodyRot + turn);
        // The remaining angle is the progress: a body whose turn rate is zero never reports progress and the watchdog ends it.
        return new Running(Math.abs(left), TravelMode.CONSUMED);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "to=%.1f turnRate=%.1f", yaw, turnRate);
    }
}
