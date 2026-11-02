package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/**
 * Step off the bank into the water beside it (进水): the vanilla way a body enters water, it walks at the water over the edge and
 * falls in - no jump and no leap of faith. Done once it is in the water; a body that cannot get there in its watchdog window
 * answers {@code NO_PROGRESS}. Nothing is placed or climbed: what it does in the water is the caller's next step.
 */
public final class StepIn implements Action {
    public static final String ID = "step_in";

    private final Vec3 water;
    private double pace;
    private Vec3 from;

    /** Steps in at {@code water}: a point of the water surface the body heads for. */
    public StepIn(Vec3 water) {
        if (water == null) throw new IllegalArgumentException("StepIn needs the water to head for");
        this.water = water;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!(Attach.of(body) instanceof AttachState.Free free) || free.inWater() || !body.onGround())
            return Feasibility.of(Reason.NO_SUPPORT, 0, "not_ashore");
        return body.level().getFluidState(BlockPos.containing(water)).is(FluidTags.WATER)
                ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_water at=" + GameplayLog.at(water));
    }

    @Override public void start(YukkuriEntity body) {
        pace = BodyAbility.of(body).walkSpeed() * BodyAbility.carrying(body);
        from = body.position();
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (body.isInWaterOrBubble()) return new Outcome.Done("in_water at=" + GameplayLog.at(body.position()));
        var to = water.subtract(body.position()).multiply(1, 0, 1);
        double distance = to.length();
        if (distance > 1E-4) {
            Motor.face(body, SurfaceApproach.yaw(body.position(), water));
            var v = to.scale(Math.min(pace, distance) / distance);
            Motor.velocity(body, new Vec3(v.x, body.getDeltaMovement().y, v.z), "step_in");
        }
        return new Running(distance, TravelMode.PASS);
    }

    @Override public String startDetail() {
        return "from=" + GameplayLog.at(from) + " water=" + GameplayLog.at(water) + String.format(Locale.ROOT, " pace=%.3f", pace);
    }
}
