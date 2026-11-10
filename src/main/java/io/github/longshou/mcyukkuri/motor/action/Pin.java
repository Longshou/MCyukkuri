package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.PinContact;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Hop behind a free body, then hold the actual contact without attaching or relocating either body.
 * The peer's Held action consumes its travel; this action alone translates the pair on supported ground. */
public final class Pin implements Action {
    private final YukkuriEntity target;
    private final int duration;
    private final Hop hop;
    private final Vec3 landing;
    private Vec3 from, failedFrom, failedTarget;
    private int ticks;
    private boolean contact;
    private Outcome result;

    public Pin(YukkuriEntity body, YukkuriEntity target, int duration) {
        this.target = target;
        this.duration = duration;
        landing = Hop.intercept(body, target, stand(body, target));
        hop = new Hop(body, landing);
    }

    /** Tangency of the two collision boxes along the target's rear bearing; neither body overlaps at release. */
    public static Vec3 stand(YukkuriEntity body, YukkuriEntity target) {
        Vec3 forward = Vec3.directionFromRotation(0, target.getYRot());
        double spacing = (body.getBbWidth() + target.getBbWidth()) * .5
                / Math.max(Math.abs(forward.x), Math.abs(forward.z));
        return target.position().subtract(forward.scale(spacing));
    }

    /** A prediction outside running reach is not an attempt; full admission still checks ground and sweep. */
    public boolean withinReach(YukkuriEntity body) { return hop.withinReach(body); }

    /** A failed attempt cannot be restarted at the same geometry: at least half a body width must change. */
    public boolean changed(YukkuriEntity body) {
        double change = Math.min(body.getBbWidth(), target.getBbWidth()) * .5;
        return body.position().distanceTo(failedFrom) >= change || target.position().distanceTo(failedTarget) >= change;
    }

    public boolean contact() { return contact && result == null; }
    public Outcome result() { return result; }
    @Override public String id() { return "pin"; }
    @Override public boolean airborne() { return !contact && hop.airborne(); }
    @Override public boolean interruptible() { return contact || hop.interruptible(); }
    @Override public MotionPose pose() { return contact ? MotionPose.NONE : hop.pose(); }

    private Feasibility free(YukkuriEntity body) {
        if (!target.isAlive() || target.level() != body.level()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_gone");
        if (target.isPassenger() || target.isVehicle() || target.isInWaterOrBubble()
                || !Hop.ground(target, target.position())) return Feasibility.of(Reason.NO_SUPPORT, 0, "target_not_on_free_ground");
        if (target.motor().current() != null && !target.motor().current().interruptible())
            return Feasibility.of(Reason.NO_CONTACT, 0, "target_in_motion");
        return Feasibility.pass();
    }

    @Override public Feasibility check(YukkuriEntity body) {
        var ready = free(body);
        return ready.ok() ? hop.check(body) : ready;
    }

    private Feasibility touching(YukkuriEntity body) {
        Vec3 offset = body.position().subtract(target.position());
        Vec3 forward = Vec3.directionFromRotation(0, target.getYRot());
        return PinContact.touches(new Vec(offset.x, offset.y, offset.z), new Vec(forward.x, 0, forward.z),
                body.getBbWidth(), target.getBbWidth()) ? Feasibility.pass()
                : Feasibility.of(Reason.TARGET_MOVED, body.position().distanceTo(stand(body, target)), "prey_left_landing");
    }

    @Override public void start(YukkuriEntity body) {
        from = body.position();
        body.getNavigation().stop();
        hop.start(body);
        GameplayLog.event(body, "agitation", "pounce start target={} distance={} reason=chase {}",
                target.getUUID(), from.distanceTo(landing), hop.startDetail());
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!contact) {
            var progress = hop.tick(body);
            if (progress instanceof Outcome && !(progress instanceof Outcome.Done)) return progress;
            if (!hop.contacted()) {
                if (progress instanceof Running running)
                    return new Running(duration + 1 + running.metric(), running.travel(), running.replanned());
                return progress;
            }
            var ready = free(body);
            if (!ready.ok()) return Outcome.of(ready.reason(), ready.shortfall(), ready.detail());
            // A moving target may evade the committed arc. Never correct the landing into contact.
            var touch = touching(body);
            if (!touch.ok()) return Outcome.of(touch.reason(), touch.shortfall(), touch.detail());
            target.motor().interrupt("pin_contact");
            contact = true;
            // Acquire during this landing's travel pass, before the prey can take another step.
            // The same owner keeps both actions alive while the behaviour observes contact next tick.
            if (!target.motor().run(body.motor().owner(), new Held(body, this))) {
                contact = false;
                var refusal = target.motor().refusal();
                return refusal != null ? refusal : new Outcome.Interrupted(target.motor().startRefusal().reason());
            }
            Motor.velocity(body, Vec3.ZERO, "pin_contact");
            GameplayLog.event(body, "agitation", "pounce land target={} distance={} gap={} reason=contact {}",
                    target.getUUID(), from.distanceTo(body.position()), body.position().distanceTo(stand(body, target)),
                    hop.endDetail(new Outcome.Done("contact")));
            return new Running(duration + 1, TravelMode.CONSUMED, true);
        }
        if (!(target.motor().current() instanceof Held held) || held.pin != this)
            return new Outcome.Interrupted("peer_released");
        var ready = free(body);
        if (!ready.ok()) return Outcome.of(ready.reason(), ready.shortfall(), ready.detail());
        if (!Hop.ground(body, body.position())) return Outcome.of(Reason.NO_SUPPORT, 0, "pin_ground_lost");
        if (++ticks > duration) return new Outcome.Done("pin_released");
        // Opposing contact pushes cancel; only net momentum (including the peer's struggle) moves the pair.
        double mass = body.getBbWidth() * body.getBbWidth() * body.getBbHeight();
        double peerMass = target.getBbWidth() * target.getBbWidth() * target.getBbHeight();
        Vec3 impulse = body.getDeltaMovement().scale(mass).add(target.getDeltaMovement().scale(peerMass))
                .scale(1 / (mass + peerMass)).multiply(1, 0, 1);
        double pace = Math.min(BodyAbility.of(body).walkSpeed(), BodyAbility.of(target).walkSpeed());
        if (impulse.length() > pace) impulse = impulse.normalize().scale(pace);
        if (clear(body, impulse) && clear(target, impulse)) {
            Motor.move(body, impulse, "pin_struggle");
            Motor.move(target, impulse, "pin_struggle");
        }
        Motor.velocity(body, Vec3.ZERO, "pin_rest");
        Motor.velocity(target, Vec3.ZERO, "pin_rest");
        float yaw = (float) Math.toDegrees(Math.atan2(target.getZ() - body.getZ(), target.getX() - body.getX())) - 90;
        turn(body, yaw);
        turn(target, yaw);
        return new Running(duration + 1 - ticks, TravelMode.CONSUMED);
    }

    private static boolean clear(YukkuriEntity body, Vec3 delta) {
        return Hop.ground(body, body.position().add(delta))
                && body.level().noCollision(body, body.getBoundingBox().expandTowards(delta));
    }

    private static void turn(YukkuriEntity body, float yaw) {
        double rate = BodyAbility.of(body).turnRate();
        Motor.face(body, body.getYRot() + (float) Mth.clamp(Mth.wrapDegrees(yaw - body.getYRot()), -rate, rate));
    }

    @Override public void abort(YukkuriEntity body, String why) {
        if (!contact) hop.abort(body, why);
    }

    @Override public void finished(YukkuriEntity body, Outcome outcome) {
        result = outcome;
        failedFrom = body.position();
        failedTarget = target.position();
        if (!contact) GameplayLog.event(body, "agitation", "pounce blocked target={} distance={} reason={} outcome={}",
                target.getUUID(), body.position().distanceTo(landing), outcome.reason(), outcome);
    }

    /** Admission refusal is the same attempt record, so it also needs changed geometry before retry. */
    public void refused(YukkuriEntity body, Outcome outcome) { finished(body, outcome); }

    /** The target's own active goal requests this at its existing struggle beat. */
    public static void struggle(YukkuriEntity body) {
        if (body.motor().current() instanceof Held held && held.pin.contact()) {
            Vec3 forward = Vec3.directionFromRotation(0, body.getYRot());
            Motor.velocity(body, forward.scale(BodyAbility.of(body).walkSpeed()), "pin_struggle");
        }
    }

    /** No relation is copied here: this peer execution observes its one owning Pin action. */
    public static final class Held implements Action {
        private final YukkuriEntity attacker;
        private final Pin pin;
        private int elapsed;
        public Held(YukkuriEntity attacker, Pin pin) { this.attacker = attacker; this.pin = pin; }
        @Override public String id() { return "pinned"; }
        @Override public Feasibility check(YukkuriEntity body) {
            return attacker.motor().current() == pin && pin.contact() && pin.target == body
                    ? pin.touching(attacker) : Feasibility.of(Reason.NO_CONTACT, 0, "pin_ended");
        }
        @Override public void start(YukkuriEntity body) {
            body.getNavigation().stop();
            Motor.velocity(body, Vec3.ZERO, "pin_caught");
        }
        @Override public Progress tick(YukkuriEntity body) {
            if (!attacker.isAlive() || attacker.motor().current() != pin || !pin.contact())
                return new Outcome.Done("pin_released");
            return new Running(pin.duration - ++elapsed, TravelMode.CONSUMED);
        }
    }
}
