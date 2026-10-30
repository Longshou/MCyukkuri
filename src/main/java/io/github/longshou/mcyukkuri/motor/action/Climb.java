package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
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
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Climb out of the water over a brim or a bank's edge: the grip holds the edge, the clip pulls up, over and down onto the stand; a host tilting past the grip is {@code SLIPPED}. */
public final class Climb implements Action {
    public static final String ID = "climb";
    private static final String CLIP = "water_hat_climb";
    /** Blocks a tick a held host may move; degrees of tilt a full grip holds; blocks of clearance over the edge; blocks a pull may lag its path before it has lost the edge. */
    private static final double HOST_SPEED = .1, GRIP_ANGLE = 45, CLEAR = .05, HOLD = .3;

    private final SurfaceHost host;
    private BodyAbility ability;
    private Vec3 edge, stand, from;
    private int t, pullStart, pullEnd;
    private long startTick;
    private double rise, top;

    private Climb(SurfaceHost host, Vec3 edge, Vec3 stand) { this.host = host; this.edge = edge; this.stand = stand; }

    /** Over the brim nearest the body into the hollow. */
    public static Climb onto(SurfaceHost host) { return new Climb(host, null, null); }

    /** Over a bank's {@code edge} to stand at {@code stand} (world). */
    public static Climb onto(Vec3 edge, Vec3 stand) { return new Climb(null, edge, stand); }

    /** The host it climbs onto, or null for a bank; the host's contact pass leaves its climber alone. */
    public SurfaceHost host() { return host; }

    @Override public String id() { return ID; }

    /** Settles the edge and the stand: world points for a bank, the host's local spots (hat units) for a host. */
    @Override public Feasibility check(YukkuriEntity body) {
        ability = BodyAbility.of(body);
        if (host != null && host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        Vec3 grip = host == null ? edge : ContactGeometry.nearestRim(body, host).orElse(null);
        if (grip == null) return Feasibility.of(Reason.NO_CONTACT, 0, "no_rim");
        double gap = ContactGeometry.gripGap(body, grip);
        if (gap > 0) return Feasibility.of(Reason.NO_CONTACT, gap, "edge=" + GameplayLog.at(grip));
        String slip = slip();
        if (slip != null) return Feasibility.of(Reason.SLIPPED, 0, slip);
        rise = grip.y - body.getY();
        if (rise > ability.climbRise())
            return Feasibility.of(Reason.TOO_HIGH, rise - ability.climbRise(), String.format(Locale.ROOT, "rise=%.2f climbRise=%.2f", rise, ability.climbRise()));
        if (host != null) {
            edge = host.frame().toLocal(grip);
            stand = inside(body);
            return stand == null ? Feasibility.of(Reason.NO_ROOM, 0, "no_room_inside") : Feasibility.pass();
        }
        return body.level().noCollision(body, body.getBoundingBox().move(stand.subtract(body.position()).add(0, CLEAR, 0)))
                ? Feasibility.pass() : Feasibility.of(Reason.NO_ROOM, 0, "stand=" + GameplayLog.at(stand));
    }

    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
        var clip = ClipMarkers.of(CLIP);
        pullStart = clip.ticks("pull_start", 17);
        pullEnd = Math.max(pullStart + 1, clip.ticks("pull_end", 31));
        from = host == null ? body.position() : host.frame().toLocal(body.position());
        top = Math.max(edge.y, stand.y) + CLEAR / (host == null ? 1 : host.frame().unit());
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (host != null && host.entity().isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        String slip = slip();
        if (slip != null) return Outcome.of(Reason.SLIPPED, 0, slip);
        Vec3 aim = along(Math.clamp((double) (++t - pullStart) / (pullEnd - pullStart), 0, 1));
        if (host != null) aim = host.frame().toWorld(aim);
        Motor.move(body, aim.subtract(body.position()), "climb");
        double behind = body.position().distanceTo(aim);
        if (behind > HOLD) return Outcome.of(Reason.SLIPPED, behind, "lost_edge at=" + GameplayLog.at(body.position()));
        if (t < pullEnd) return new Running(pullEnd - t, TravelMode.CONSUMED);
        Motor.velocity(body, Vec3.ZERO, "climb");
        if (host == null) {
            Motor.move(body, new Vec3(0, -2 * CLEAR, 0), "climb_settle");
            return new Outcome.Done("on_bank at=" + GameplayLog.at(body.position()));
        }
        if (!Motor.mount(body, host.entity(), "climb")) return Outcome.of(Reason.NO_ROOM, 0, "mount_refused");
        host.riders().put(body.getUUID(), stand, Mth.wrapDegrees(body.getYRot() - (float) Math.toDegrees(host.frame().yawRad())));
        return new Outcome.Done("aboard local=" + stand);
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

    /** The supported spot with room nearest the middle on the line in from the gripped brim (hat units), or null. */
    private Vec3 inside(YukkuriEntity body) {
        double reach = Math.hypot(edge.x, edge.z), foot = Occupancy.foot(body.getBbWidth());
        for (double r = 0; r <= reach - foot / host.frame().unit(); r += .5) {
            var height = host.support(new Vec3(edge.x / reach * r, 0, edge.z / reach * r), foot);
            if (height.isPresent() && host.room(body, new Vec3(edge.x / reach * r, height.getAsDouble(), edge.z / reach * r)))
                return new Vec3(edge.x / reach * r, height.getAsDouble(), edge.z / reach * r);
        }
        return null;
    }

    /** A share {@code f} along the pull: straight up from where it gripped, across at the edge's height, down onto the stand. */
    private Vec3 along(double f) {
        Vec3 up = new Vec3(from.x, top, from.z), across = new Vec3(stand.x, top, stand.z);
        double a = up.distanceTo(from), b = across.distanceTo(up), c = stand.distanceTo(across), s = f * (a + b + c);
        if (s <= a) return a <= 0 ? up : from.lerp(up, s / a);
        return s <= a + b ? up.lerp(across, b <= 0 ? 1 : (s - a) / b) : across.lerp(stand, c <= 0 ? 1 : (s - a - b) / c);
    }
}
