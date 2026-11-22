package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import java.util.Locale;
import java.util.function.DoubleSupplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Push for one {@code push} clip: a hat gains the pusher's pace; another body uses their box-volume share. */
public final class Push implements Action {
    public static final String ID = "push";

    private final Entity target;
    /** A drive-off: blocks the target still lies inside what it is driven out of; null for one push. */
    private final DoubleSupplier remaining;
    private double gain;
    private int t, length;
    private long startTick;
    private Vec3 from;

    public Push(Entity target) { this(target, null); }

    /** A drive-off for one {@code push} clip: one action follows and shoves the target until {@code remaining} reaches zero, so the
     *  clip keeps its phase instead of starting again between shoves. */
    public Push(Entity target, DoubleSupplier remaining) {
        this.target = target;
        this.remaining = remaining;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!target.isAlive() || target.level() != body.level()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_gone");
        if (!(target instanceof YukkuriEntity || target instanceof RemainsEntity) && !target.isPushable())
            return Feasibility.of(Reason.TOO_HEAVY, 0, "not_pushable");
        double gap = ContactGeometry.mouthGap(body, target) - ContactGeometry.GRAB_MARGIN;
        if (gap > 0) return Feasibility.of(Reason.NO_CONTACT, gap, String.format(Locale.ROOT, "gap=%.2f", gap));
        if (target instanceof RemainsEntity && (!body.onGround() || !target.onGround() || !ContactGeometry.inMouthReach(body, target)))
            return Feasibility.of(Reason.NO_CONTACT, 0, "remains_out_of_reach");
        gain = BodyAbility.of(body).pace(body.isInWaterOrBubble());
        if (target instanceof YukkuriEntity other) {
            var contact = groundContact(body, other);
            if (!contact.ok()) return contact;
            gain *= Effort.share(body.getBbWidth() * body.getBbWidth() * body.getBbHeight(),
                    other.getBbWidth() * other.getBbWidth() * other.getBbHeight());
        }
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        length = ClipMarkers.of(ID).length();
        startTick = body.level().getGameTime();
        from = target.position();
    }

    private static Feasibility groundContact(YukkuriEntity body, YukkuriEntity other) {
        if (!ContactGeometry.inMouthReach(body, other)) return Feasibility.of(Reason.NO_CONTACT, 0, "target_out_of_reach");
        if (!body.onGround() || !other.onGround() || other.isPassenger() || other.isVehicle() || other.operations().active())
            return Feasibility.of(Reason.NO_SUPPORT, 0, "target_not_free_on_floor");
        Vec3 direction = other.position().subtract(body.position()).multiply(1, 0, 1).normalize();
        Vec3 at = other.position().add(direction.scale(other.getBbWidth()));
        return SurfaceApproach.stands(other, at) && SurfaceApproach.dryStep(other, other.position(), at)
                ? Feasibility.pass() : Feasibility.of(Reason.NO_SUPPORT, 0, "no_dry_step");
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!target.isAlive() || target.level() != body.level()) return Outcome.of(Reason.TARGET_GONE, 0, "target_gone");
        if (remaining != null) return driveOff(body);
        if (target instanceof YukkuriEntity other) {
            var contact = groundContact(body, other);
            if (!contact.ok()) return t > 0 ? new Outcome.Done("contact ticks=" + t)
                    : Outcome.of(contact.reason(), contact.shortfall(), contact.detail());
            Motor.face(body, SurfaceApproach.yaw(body.position(), target.position()));
        }
        if (++t > length) return new Outcome.Done("pushed ticks=" + length);
        if (target instanceof RemainsEntity) {
            if (!body.onGround()) return Outcome.of(Reason.NO_SUPPORT, 0, "pusher_lost_support");
            if (!target.onGround() || !ContactGeometry.inMouthReach(body, target)) return new Running(length - t, TravelMode.PASS);
        }
        shove(body);
        return new Running(length - t, TravelMode.PASS);
    }

    /** One shove at a time, but one action: out of reach it steps after the target, in reach it shoves again, and it ends only when
     *  the target has left what it is driven out of ({@code remaining} at zero) or cannot be reached or moved. */
    private Progress driveOff(YukkuriEntity body) {
        double left = remaining.getAsDouble();
        if (left <= 0) return new Outcome.Done("out_of_range");
        if (target instanceof YukkuriEntity other) {
            if (groundContact(body, other).ok()) {
                Motor.face(body, SurfaceApproach.yaw(body.position(), target.position()));
                shove(body);
            } else follow(body, other);
        } else shove(body);
        return new Running(left, TravelMode.PASS);
    }

    /** A step after the one being driven out, to the nearest stand it can shove from again. */
    private static void follow(YukkuriEntity body, YukkuriEntity other) {
        double spacing = (body.getBbWidth() + other.getBbWidth()) * .5 + .1;
        var stand = SurfaceApproach.beside(body, other, spacing, ContactGeometry.GRAB_HEIGHT, Double.POSITIVE_INFINITY,
                at -> ContactGeometry.inMouthReach(body, at, other) && SurfaceApproach.roomAt(body, at, other));
        if (stand != null) SurfaceApproach.walkToContact(body, stand, other, 1);
        else SurfaceApproach.stopWalking(body);
    }

    private void shove(YukkuriEntity body) {
        Vec3 away = target.position().subtract(body.position()).multiply(1, 0, 1);
        Vec3 push = away.lengthSqr() < 1E-8 ? Vec3.ZERO : away.normalize().scale(gain);
        if (target instanceof YukkuriEntity other) Motor.velocity(other, other.getDeltaMovement().add(push), "pushed");
        else target.push(push.x, 0, push.z);
    }

    /** A drive-off may have been stepping after its target: the body is left standing where it got to. */
    @Override public void finished(YukkuriEntity body, Outcome outcome) {
        if (remaining != null) SurfaceApproach.stopWalking(body);
    }

    @Override public String startDetail() { return String.format(Locale.ROOT, "target=%s gain=%.3f", target.getUUID(), gain); }

    @Override public MotionPose pose() { return new MotionPose(ID, startTick, remaining != null); }

    @Override public String endDetail(Outcome outcome) {
        return target instanceof YukkuriEntity ? String.format(Locale.ROOT, "target=%s moved=%.3f", target.getUUID(),
                from.subtract(target.position()).horizontalDistance()) : "";
    }
}
