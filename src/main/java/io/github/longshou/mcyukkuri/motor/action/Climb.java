package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
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
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/** Climb out of the water over a brim or a bank's edge: the grip holds the edge, the clip pulls up, over and down onto the stand; a host tilting past the grip is {@code SLIPPED}. A bank climb ends only on a stand the body really rests on ({@link SurfaceApproach#stands}), never on a touch of the edge. */
public final class Climb implements Action {
    public static final String ID = "climb";
    private static final String CLIP = "water_hat_climb";
    /** Blocks a tick a held host may move; degrees of tilt a full grip holds; blocks of clearance over the edge; blocks a pull may lag its path before it has lost the edge. */
    private static final double HOST_SPEED = .1, GRIP_ANGLE = 45, CLEAR = .05, HOLD = .3;
    /** Energy a strained scramble over a bank edge above the plain climb rise costs (a stroke of Beckon costs 1; a hop 1): paid at the start. */
    public static final float STRAIN_COST = 5;

    private final SurfaceHost host;
    private BodyAbility ability;
    private Vec3 edge, stand, from;
    private int t, pullStart, pullEnd;
    private long startTick;
    private double rise, top;
    private Hop seating;

    private Climb(SurfaceHost host, Vec3 edge, Vec3 stand) { this.host = host; this.edge = edge; this.stand = stand; }

    /** Over the brim nearest the body into the hollow. */
    public static Climb onto(SurfaceHost host) { return new Climb(host, null, null); }

    /** Over a bank's {@code edge} to stand at {@code stand} (world). */
    public static Climb onto(Vec3 edge, Vec3 stand) { return new Climb(null, edge, stand); }

    /** The host it climbs onto, or null for a bank; the host's contact pass leaves its climber alone. */
    public SurfaceHost host() { return host; }

    @Override public String id() { return ID; }
    @Override public boolean airborne() { return seating != null && seating.airborne(); }

    /** Settles the edge and the stand: world points for a bank, the host's local spots (hat units) for a host. */
    @Override public Feasibility check(YukkuriEntity body) {
        ability = BodyAbility.of(body);
        if (host != null && host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        Vec3 grip = host == null ? edge : ContactGeometry.nearestRim(body, host).orElse(null);
        if (grip == null) return Feasibility.of(Reason.NO_CONTACT, 0, "no_rim");
        double gap = ContactGeometry.gripGap(body, grip) - ContactGeometry.GRIP_SETTLE;
        if (gap > 0) return Feasibility.of(Reason.NO_CONTACT, gap, "edge=" + GameplayLog.at(grip));
        String slip = slip();
        if (slip != null) return Feasibility.of(Reason.SLIPPED, 0, slip);
        rise = grip.y - body.getY();
        // A bank edge between the plain climb and the strained scramble (hop and braid hook) costs energy a spent body lacks.
        double reach = host == null ? ability.scrambleRise() : ability.climbRise();
        if (rise > reach)
            return Feasibility.of(Reason.TOO_HIGH, rise - reach, String.format(Locale.ROOT, "rise=%.2f climbRise=%.2f scrambleRise=%.2f", rise, ability.climbRise(), ability.scrambleRise()));
        if (host == null && rise > ability.climbRise() && body.life().energy() < STRAIN_COST)
            return Feasibility.of(Reason.EXHAUSTED, 0, String.format(Locale.ROOT, "energy=%.1f strain=%.1f", body.life().energy(), STRAIN_COST));
        if (host != null) {
            edge = host.frame().toLocal(grip);
            // A hull this body's own boarding would overload flips her straight back: it is no way out (the captain included).
            String overload = host.boardingRefusal(body);
            if (overload != null) return Feasibility.of(Reason.TOO_HEAVY, 0, "overload " + overload);
            var entry = host.restEntry(body);
            if (entry == null || !entry.available(body)) return Feasibility.of(Reason.NO_ROOM, 0, "no_room_inside");
            stand = entry.local();
            return Feasibility.pass();
        }
        // The landing is a stand a body really rests on, not just a point with room: its real sole height is what it aims at.
        Vec3 floorAt = SurfaceApproach.floor(body, stand);
        if (floorAt == null) return Feasibility.of(Reason.NO_SUPPORT, 0, "stand=" + GameplayLog.at(stand));
        stand = floorAt;
        return Feasibility.pass();
    }

    @Override public MotionPose pose() { return seating == null ? new MotionPose(CLIP, startTick, false) : seating.pose(); }
    @Override public boolean interruptible() { return t < pullStart; }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
        var clip = ClipMarkers.of(CLIP);
        pullStart = clip.ticks("pull_start", 17);
        pullEnd = Math.max(pullStart + 1, clip.ticks("pull_end", 31));
        from = host == null ? body.position() : host.frame().toLocal(body.position());
        top = Math.max(edge.y, stand.y) + CLEAR / (host == null ? 1 : host.frame().unit());
        body.speakCue("self_rescue_grip");
        if (host == null && rise > ability.climbRise()) {
            body.life().spendEnergy(STRAIN_COST);
            body.showReaction("puff_teary", null, pullEnd);
            GameplayLog.event(body, "move", "scramble strain rise={} climbRise={} scrambleRise={} energy={}",
                    String.format(Locale.ROOT, "%.2f", rise), String.format(Locale.ROOT, "%.2f", ability.climbRise()),
                    String.format(Locale.ROOT, "%.2f", ability.scrambleRise()), String.format(Locale.ROOT, "%.1f", body.life().energy()));
        }
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (seating != null) return seating.tick(body);
        if (host != null && host.entity().isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        String slip = slip();
        if (slip != null) return Outcome.of(Reason.SLIPPED, 0, slip);
        Vec3 aim = along(Math.clamp((double) (++t - pullStart) / (pullEnd - pullStart), 0, 1));
        if (host != null) aim = host.frame().toWorld(aim);
        Motor.move(body, aim.subtract(body.position()), "climb");
        double behind = body.position().distanceTo(aim);
        if (behind > HOLD) return Outcome.of(Reason.SLIPPED, behind, "lost_edge at=" + GameplayLog.at(body.position()));
        if (t == pullStart + 1) body.speakCue("self_rescue_climb");
        if (t < pullEnd) return new Running(pullEnd - t, TravelMode.CONSUMED);
        Motor.velocity(body, Vec3.ZERO, "climb");
        if (host == null) {
            Motor.move(body, new Vec3(0, -2 * CLEAR, 0), "climb_settle");
            // Out of the water only on the stand itself: a body that stops short stays in the water and the way failed.
            if (!SurfaceApproach.stands(body, body.position()))
                return Outcome.of(Reason.NO_SUPPORT, 0, "off_stand at=" + GameplayLog.at(body.position()) + " stand=" + GameplayLog.at(stand));
            return new Outcome.Done("on_bank at=" + GameplayLog.at(body.position()) + " support=" + GameplayLog.at(stand));
        }
        if (!new SeatTarget(host, stand).land(body, "climb")) return Outcome.of(Reason.NO_ROOM, 0, "landing_refused");
        seating = new Hop(host);
        seating.start(body);
        return seating.seatFromEntry(body);
    }

    @Override public void finished(YukkuriEntity body, Outcome outcome) {
        if (outcome.reason() == Reason.SLIPPED) body.speakCue("self_rescue_slip");
        else if (outcome instanceof Outcome.Done && (host == null || host.settled(body)))
            body.speakCue("self_rescue_safe");
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "onto=%s rise=%.2f climbRise=%.2f grip=%.2f pull=%d..%d", host == null ? "bank" : host.entity().getUUID(),
                rise, ability.climbRise(), ability.grip(), pullStart, pullEnd);
    }

    /** The host's tilt or speed tears the grip, or it turned over. */
    private String slip() {
        if (host == null) return null;
        double tilt = Math.max(Math.abs(host.frame().rollDeg()), Math.abs(host.frame().pitchDeg())), holds = GRIP_ANGLE * ability.grip();
        double speed = host.entity().getDeltaMovement().horizontalDistance();
        return host.capsized() ? "capsized" : tilt > holds ? String.format(Locale.ROOT, "tilt=%.1f holds=%.1f", tilt, holds)
                : speed > HOST_SPEED ? String.format(Locale.ROOT, "host_speed=%.3f", speed) : null;
    }

    /** A share {@code f} along the pull: one smooth arc up and over the brim/edge into the stand, clearing {@code top} before crossing the rim. */
    private Vec3 along(double f) {
        double q = Math.clamp(f, 0, 1);
        double h = q * q * (3 - 2 * q);
        double x = from.x + (stand.x - from.x) * h;
        double z = from.z + (stand.z - from.z) * h;
        double arch = (CLEAR / (host == null ? 1 : host.frame().unit())) * 1.2 * Math.sin(Math.PI * q);
        double y;
        if (q <= 0.32) {
            y = from.y + (top - from.y) * Math.sin((q / 0.32) * (Math.PI / 2)) + arch;
        } else {
            double v = (q - 0.32) / 0.68;
            y = top + (stand.y - top) * (v * v) + arch;
        }
        return new Vec3(x, y, z);
    }
}
