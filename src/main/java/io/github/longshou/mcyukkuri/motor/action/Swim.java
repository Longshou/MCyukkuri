package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Swim continuously at the body's current ability; vanilla resolves travel and the water supplies lift. */
public final class Swim implements Action {
    public static final String ID = "swim";
    /** Energy per tick, preserving the rested/exhausted effort cost over time. */
    private static final float COST = .65f / 32, WEAK_COST = .3f / 64;

    private final Supplier<Vec3> target;
    private final boolean grip;
    private final int maxTicks;
    private double speed, turnRate, paid;
    private int ticks;
    private boolean unpaid;
    /** The clip's own start: the tick the plunge's impact ended, shared by every leg of one immersion. */
    private long stroke;
    private boolean stroking;

    private Swim(Supplier<Vec3> target, boolean grip, int maxTicks) { this.target = target; this.grip = grip; this.maxTicks = maxTicks; }

    /** Until the (moving) target is in grip reach; past {@code maxTicks} (0: never) it is {@code TOO_FAR}. */
    public static Swim to(Supplier<Vec3> target, int maxTicks) { return new Swim(target, true, maxTicks); }

    /** Pressing toward a point for {@code ticks}, then done (the caller may aim again). */
    public static Swim toward(Vec3 point, int ticks) { return new Swim(() -> point, false, ticks); }

    /** Whether this tick's swimming effort was paid. */
    public boolean pushing() { return !unpaid; }

    /** Her own stroke from the tick the plunge ended, while she really pays for it; the distress clips are for real distress (an
     *  impact still falling, effort she cannot pay, an exhausted body). */
    @Override public MotionPose pose() { return stroking ? new MotionPose(YukkuriAction.WATER_SWIM.id(), stroke, true) : MotionPose.NONE; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (target.get() == null) return Feasibility.of(Reason.TARGET_GONE, 0, "no_target");
        ability(body);
        return body.isInWaterOrBubble() ? Feasibility.pass() : Feasibility.of(Reason.NO_SUPPORT, 0, "not_in_water");
    }

    @Override public void start(YukkuriEntity body) {}

    @Override public Progress tick(YukkuriEntity body) {
        Vec3 at = target.get();
        if (at == null) return Outcome.of(Reason.TARGET_GONE, 0, "target_gone");
        // One stroke clock for the whole immersion: the phase the water distress started in, not this leg's own start.
        var distress = body.waterDistress();
        if (distress.phase() == WaterDistress.Phase.STRUGGLE) stroke = body.level().getGameTime() - distress.phaseTicks();
        if (grip && ContactGeometry.canGrip(body, at)) {
            Motor.velocity(body, Vec3.ZERO, "swim_grip");
            return new Outcome.Done("grip at=" + GameplayLog.at(at));
        }
        if (!body.isInWaterOrBubble()) return body.onGround() || body.isPassenger()
                ? new Outcome.Done("out_of_water at=" + GameplayLog.at(body.position()))
                : new Running(body.position().distanceTo(at), TravelMode.PASS);
        Vec3 to = at.subtract(body.position()).multiply(1, 0, 1);
        double distance = to.length();
        if (maxTicks > 0 && ++ticks > maxTicks)
            return grip ? Outcome.of(Reason.TOO_FAR, distance, "swum=" + maxTicks) : new Outcome.Done("pressed ticks=" + maxTicks);
        boolean weak = body.waterDistress().phase() == WaterDistress.Phase.EXHAUSTED;
        float cost = weak ? WEAK_COST : COST;
        unpaid = !body.life().spendEnergy(cost);
        if (!unpaid) paid += cost;
        ability(body);
        if (distance > 1E-4) {
            Motor.face(body, body.getYRot() + (float) Effort.turn(body.getYRot(), Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG - 90, turnRate));
            Vec3 v = to.scale(Math.min(speed, distance) / distance);
            Motor.velocity(body, new Vec3(v.x, body.getDeltaMovement().y, v.z), "swim");
        }
        stroking = pushing() && distress.phase() == WaterDistress.Phase.STRUGGLE;
        return new Running(distance, TravelMode.PASS);
    }

    /** Pressing on has no progress watchdog: its own ticks end it. */
    @Override public int progressWindow() { return grip ? Action.super.progressWindow() : Integer.MAX_VALUE; }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "to=%s swim=%.3f turn=%.1f grip=%b max=%d", GameplayLog.at(target.get()), speed, turnRate, grip, maxTicks);
    }

    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "paid=%.2f unpaid=%b", paid, unpaid); }

    private void ability(YukkuriEntity body) {
        var ability = BodyAbility.of(body);
        speed = ability.swimSpeed() * BodyAbility.carrying(body);
        turnRate = ability.turnRate();
    }
}
