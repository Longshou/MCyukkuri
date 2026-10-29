package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * A short body gesture (spec 9.4): a small hop in place, a squash or lean that only poses, or a tumble toward a container
 * centre. On a host's brim the tumble goes into the hollow when the hollow has room on the way, else off the brim's outer
 * side, so a body that fell onto a brim does not stay balanced there. Nothing here names a height, a distance or a duration:
 * the hop apex is {@code min(0.25·S, hopHeight)} resolved through {@link ArcSolver#vyFor}, the roll is bounded by
 * {@link #ROLL_REACH} of the spec's own 0.3 blocks, and every pose length comes from {@link ClipMarkers}.
 */
public final class Gesture implements Action {
    public static final String ID = "gesture";
    /** The gesture kinds of spec 9.4; only {@link Kind#ROLL_IN} carries a target. */
    public enum Kind { HOP_SMALL, SQUASH, LEAN, ROLL_IN }

    /** A small hop rises at most this fraction of the body's own size S (spec 9.4 Gesture HOP_SMALL). */
    private static final double SMALL = .25;
    /** The most a ROLL_IN travels toward the container centre (spec 9.4: at most 0.3 blocks in total). */
    public static final double ROLL_REACH = .3;

    private final Kind kind;
    private final Vec3 toward;
    private final String clip;
    private final int length;
    private BodyAbility ability;
    /** A tumble on this host's brim, and whether it goes off the outer side (the hollow had no room) or stopped on the way in. */
    private SurfaceHost host;
    private boolean outward, stopped;
    private double vy;
    private double airSpan;
    private double rolled;
    private int takeoff;
    private int ticks;
    private int air;
    private boolean launched;
    private long startTick;

    public Gesture(Kind kind) {
        this(kind, null);
    }

    public Gesture(Kind kind, Vec3 toward) {
        if (kind == null) throw new IllegalArgumentException("A gesture needs its kind");
        if (kind == Kind.ROLL_IN && toward == null) throw new IllegalArgumentException("ROLL_IN needs a container centre");
        this.kind = kind;
        this.toward = toward;
        this.clip = clip(kind);
        this.length = ClipMarkers.of(this.clip).length();
    }

    /** The authored clip each kind plays; every one of them already exists in the exported corrections. */
    public static String clip(Kind kind) {
        return switch (kind) {
            case HOP_SMALL -> "hop";
            case SQUASH -> "stretch_shape";
            case LEAN -> "side_bend";
            case ROLL_IN -> "newborn_crawl";
        };
    }

    /** The apex of the smallest hop this body can make (spec 9.4): {@code min(0.25·S, hopHeight)}, zero when it cannot jump. */
    public static double smallHopApex(BodyAbility ability) {
        return Math.min(SMALL * ability.scale(), ability.hopHeight());
    }

    /**
     * The single upward impulse of a small hop, from the discrete arc model. Zero for a body that cannot jump, so a landing
     * bounce sized by it gets no hop rather than a made-up height.
     */
    public static double smallHopImpulse(YukkuriEntity body) {
        return smallHopImpulse(BodyAbility.of(body));
    }

    public static double smallHopImpulse(BodyAbility ability) {
        return ability.canJump() ? ArcSolver.vyFor(smallHopApex(ability)) : 0;
    }

    /**
     * Moves {@code body} toward {@code center} by at most {@code budget} blocks with collision, and reports the distance it
     * actually covered.
     */
    public static double roll(YukkuriEntity body, Vec3 center, double budget) {
        if (body == null || center == null || budget <= 0) return 0;
        Vec3 flat = center.subtract(body.position()).multiply(1, 0, 1);
        double reach = flat.horizontalDistance();
        if (reach < 1E-6) return 0;
        double step = Math.min(budget, reach);
        Vec3 before = body.position();
        Motor.move(body, new Vec3(flat.x / reach * step, 0, flat.z / reach * step), "gesture_roll");
        return before.subtract(body.position()).horizontalDistance();
    }

    @Override public String id() { return ID; }

    /** Pure: a small hop needs the jump; a tumble on a host's brim reads here which way it goes (kept for {@link #start}). */
    @Override public Feasibility check(YukkuriEntity body) {
        ability = BodyAbility.of(body);
        if (kind == Kind.HOP_SMALL && !ability.canJump())
            return Feasibility.of(Reason.TOO_HIGH, 0, "cannot_jump");
        host = kind == Kind.ROLL_IN && Attach.of(body) instanceof AttachState.OnHost on ? on.host() : null;
        outward = host != null && !hollowAhead(body);
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        ability = BodyAbility.of(body);
        vy = smallHopImpulse(ability);
        airSpan = Ballistic.airTicks(vy, 0);
        takeoff = kind == Kind.HOP_SMALL ? Hop.takeoffTicks() : 0;
        rolled = 0;
        stopped = false;
        ticks = 0;
        air = 0;
        launched = false;
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        ticks++;
        return switch (kind) {
            case HOP_SMALL -> hopTick(body);
            case SQUASH, LEAN -> poseTick();
            case ROLL_IN -> rollTick(body);
        };
    }

    private Progress hopTick(YukkuriEntity body) {
        if (!launched) {
            // The same crouch-then-one-impulse beat as a solved hop, so the two clips agree with the physics.
            if (ticks < takeoff) return new Running(takeoff - ticks + airSpan, TravelMode.CONSUMED);
            Motor.velocity(body, new Vec3(0, vy, 0), "gesture_hop");
            launched = true;
            return new Running(airSpan, TravelMode.PASS);
        }
        air++;
        if (body.isInWater()) return new Outcome.Done("water at=" + GameplayLog.at(body.position()));
        if (body.onGround()) return new Outcome.Done("landed at=" + GameplayLog.at(body.position()));
        if (air > airSpan + Hop.SETTLE) return new Outcome.Done("settled airTicks=" + air);
        return new Running(airSpan + Hop.SETTLE - air, TravelMode.PASS);
    }

    private Progress poseTick() {
        if (ticks >= length) return new Outcome.Done("clip=" + clip + " ticks=" + ticks);
        return new Running(length - ticks, TravelMode.CONSUMED);
    }

    private Progress rollTick(YukkuriEntity body) {
        double step = Math.min(ROLL_REACH - rolled, ROLL_REACH / Math.max(1, length));
        if (step > 0 && !stopped) rolled += aboard(body) ? hostRoll(body, step) : host != null ? 0 : roll(body, toward, step);
        boolean aboard = aboard(body);
        if (rolled >= ROLL_REACH - 1E-6 || ticks >= length || stopped || host != null && !aboard)
            return new Outcome.Done(String.format(Locale.ROOT, "clip=%s moved=%.2f toward=%s%s", clip, rolled,
                    GameplayLog.at(toward), host == null ? "" : outward ? aboard ? " outward" : " rolled_off" : stopped ? " blocked" : " inward"));
        // A tumble off a soft rim falls as it rolls: vanilla travel keeps gravity (PASS), the roll step is the only push; on a
        // host the host carries it and the roll writes its spot there (CONSUMED).
        return new Running(length - ticks, aboard ? TravelMode.CONSUMED : TravelMode.PASS);
    }

    /** Still riding the host it started its tumble on. */
    private boolean aboard(YukkuriEntity body) {
        return host != null && Attach.of(body) instanceof AttachState.OnHost on && on.host() == host;
    }

    /**
     * Whether the host's hollow has a free spot for this body on the way in from where it lies to the hollow's middle: inside
     * the hollow ({@link Occupancy#INSIDE_SUPPORTED}) with room at its height.
     */
    private boolean hollowAhead(YukkuriEntity body) {
        var frame = host.frame();
        double unit = Math.max(1E-6, frame.unit()), foot = Occupancy.foot(body.getBbWidth());
        Vec3 spot = host.riders().local(body.getUUID()), middle = frame.toLocal(toward);
        double dx = middle.x - spot.x, dz = middle.z - spot.z, d = Math.hypot(dx, dz);
        for (double s = 0; s <= d + 1E-9; s += foot / unit) {
            double f = d < 1E-6 ? 0 : s / d;
            Vec3 place = new Vec3(spot.x + dx * f, 0, spot.z + dz * f);
            var height = host.support(place, foot);
            if (height.isEmpty()) continue;
            Vec3 candidate = new Vec3(place.x, height.getAsDouble(), place.z);
            if (Occupancy.at(host.container(), frame.toWorld(candidate), body.getBbWidth(), true) == Occupancy.INSIDE_SUPPORTED
                    && host.room(body, candidate)) return true;
        }
        return false;
    }

    /**
     * One tick of a tumble on the host's brim (blocks covered): toward the hollow's middle when {@link #hollowAhead} found room,
     * else away from it down the brim's outer side. The spot moves along the surface (the host carries the body there); a step
     * the way in that another body or store takes stops the tumble, a step out with no surface left under the body leaves the
     * host there, moving that way, and it falls from the brim.
     */
    private double hostRoll(YukkuriEntity body, double budget) {
        var frame = host.frame();
        double unit = Math.max(1E-6, frame.unit()), foot = Occupancy.foot(body.getBbWidth());
        Vec3 spot = host.riders().local(body.getUUID()), middle = frame.toLocal(toward);
        double dx = middle.x - spot.x, dz = middle.z - spot.z, d = Math.hypot(dx, dz);
        if (d < 1E-6) { dx = 1; dz = 0; d = 1; }
        double step = outward ? budget / unit : Math.min(budget / unit, d), sign = outward ? -1 : 1;
        Vec3 next = new Vec3(spot.x + sign * dx / d * step, 0, spot.z + sign * dz / d * step);
        var height = host.support(next, foot);
        if (height.isEmpty()) {
            Vec3 from = frame.toWorld(spot), at = frame.toWorld(new Vec3(next.x, spot.y, next.z));
            Motor.dismount(body, at, at.subtract(from).add(host.entity().getDeltaMovement()), "rolled_off");
            return step * unit;
        }
        next = new Vec3(next.x, height.getAsDouble(), next.z);
        if (!host.room(body, next)) { stopped = true; return 0; }
        host.riders().put(body.getUUID(), next, host.riders().yaw(body.getUUID()));
        return step * unit;
    }

    /** Before a small hop takes off it can be abandoned; after the impulse the physics owns the body. */
    @Override public boolean interruptible() { return !launched; }

    @Override public MotionPose pose() { return new MotionPose(clip, startTick, false); }

    @Override public String startDetail() {
        if (ability == null) return "";
        return kind == Kind.HOP_SMALL
                ? String.format(Locale.ROOT, "kind=%s apex=%.2f vy=%.2f ticks=%.0f ability=h%.2f/s%.2f fp=%s",
                        kind, smallHopApex(ability), vy, airSpan, ability.hopHeight(), ability.scale(), ability.fingerprint())
                : String.format(Locale.ROOT, "kind=%s clipTicks=%d toward=%s%s", kind, length,
                        toward == null ? "none" : GameplayLog.at(toward), host == null ? "" : outward ? " brim=off_outer_side" : " brim=into_hollow");
    }

    @Override public String endDetail(Outcome outcome) {
        return kind == Kind.ROLL_IN ? String.format(Locale.ROOT, "moved=%.2f", rolled) : "";
    }
}
