package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
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
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * A short body gesture (spec 9.4): a small hop in place, a squash or lean that only poses, or a tumble toward a container
 * centre. Nothing here names a height, a distance or a duration: the hop apex is {@code min(0.25·S, hopHeight)} resolved
 * through {@link ArcSolver#vyFor}, the roll is bounded by {@link #ROLL_REACH} of the spec's own 0.3 blocks, and every pose
 * length comes from {@link ClipMarkers}.
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

    @Override public Feasibility check(YukkuriEntity body) {
        ability = BodyAbility.of(body);
        if (kind == Kind.HOP_SMALL && !ability.canJump())
            return Feasibility.of(Reason.TOO_HIGH, 0, "cannot_jump");
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        ability = BodyAbility.of(body);
        vy = smallHopImpulse(ability);
        airSpan = Ballistic.airTicks(vy, 0);
        takeoff = kind == Kind.HOP_SMALL ? Hop.takeoffTicks() : 0;
        rolled = 0;
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
        if (step > 0) rolled += roll(body, toward, step);
        if (rolled >= ROLL_REACH - 1E-6 || ticks >= length)
            return new Outcome.Done(String.format(Locale.ROOT, "clip=%s moved=%.2f toward=%s", clip, rolled,
                    GameplayLog.at(toward)));
        // A tumble off a soft rim falls as it rolls: vanilla travel keeps gravity (PASS), the roll step is the only push.
        return new Running(length - ticks, TravelMode.PASS);
    }

    /** Before a small hop takes off it can be abandoned; after the impulse the physics owns the body. */
    @Override public boolean interruptible() { return !launched; }

    @Override public MotionPose pose() { return new MotionPose(clip, startTick, false); }

    @Override public String startDetail() {
        if (ability == null) return "";
        return kind == Kind.HOP_SMALL
                ? String.format(Locale.ROOT, "kind=%s apex=%.2f vy=%.2f ticks=%.0f ability=h%.2f/s%.2f fp=%s",
                        kind, smallHopApex(ability), vy, airSpan, ability.hopHeight(), ability.scale(), ability.fingerprint())
                : String.format(Locale.ROOT, "kind=%s clipTicks=%d toward=%s", kind, length,
                        toward == null ? "none" : GameplayLog.at(toward));
    }

    @Override public String endDetail(Outcome outcome) {
        return kind == Kind.ROLL_IN ? String.format(Locale.ROOT, "moved=%.2f", rolled) : "";
    }
}
