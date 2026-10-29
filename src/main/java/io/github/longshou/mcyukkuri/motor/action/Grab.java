package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.ParentCarry.Hold;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.ContactGeometry.Reach;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * Take a body into one of the carrier's holds (spec 9.4): the carrier's rules ({@code ParentCarry.holdsFor}) give the holds
 * that can take it (brim, tail, mouth, braid tip), and the Grab takes the first one whose reach gets it: the mouth's rule,
 * or the braid swung out upright or in the low lean. It must not be in the air or in someone else's hold. The braid first
 * turns its side to the target; after the lift the target becomes a real passenger, placed by its hold ({@code ParentCarry.seat}).
 * The hold is recorded at the start ({@code ParentCarry.plan}), which is also what the client's reach pose reads.
 */
public final class Grab implements Action {
    public static final String ID = "grab";
    /** The mouth lift (ticks): the parent stands over the child and closes on it. */
    public static final int LIFT_TICKS = 14;
    /** The braid's clips: it reaches, curls round the child and lifts it by {@code lift_end}; the low lean reaches lower and farther. */
    public static final String BRAID_LIFT = "braid_cradle_lift", BRAID_LEAN = "braid_reach_low";
    /** How far the target may drift during the lift before the grab gives up (blocks). */
    private static final double DRIFT = .3;

    private final YukkuriEntity target;
    private Hold hold = Hold.MOUTH;
    private boolean lean;
    private double gap;
    private Face turn;
    private Vec3 at;
    /** A lift out of the water (a rescue): a target in the water is taken; any other grab ends when the target is in the water. */
    private final boolean outOfWater;
    private int ticks, lift;
    private long startTick;

    public Grab(YukkuriEntity target) { this(target, false); }

    private Grab(YukkuriEntity target, boolean outOfWater) {
        if (target == null) throw new IllegalArgumentException("Grab needs a target");
        this.target = target;
        this.outOfWater = outOfWater;
    }

    /** The same grab of a body in the water, lifting it out (the caller has decided to rescue it). */
    public static Grab outOfWater(YukkuriEntity target) { return new Grab(target, true); }

    @Override public String id() { return ID; }

    /** The body being lifted: until it is mounted, a host leaves it where it is. */
    public YukkuriEntity target() { return target; }

    /** The hold the last {@link #check} chose (its reach is {@code hold().reach()}). */
    public Hold hold() { return hold; }

    @Override public Feasibility check(YukkuriEntity carrier) {
        if (!target.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_gone");
        if (target.level() != carrier.level()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_level");
        AttachState attach = Attach.of(target);
        if (attach instanceof AttachState.Held held)
            return Feasibility.of(Reason.NO_ROOM, 0, held.carrier() == carrier ? "already_held" : "target_held");
        if (attach instanceof AttachState.Airborne) return Feasibility.of(Reason.NO_CONTACT, 0, "target_airborne");
        var holds = ParentCarry.holdsFor(carrier, target);
        if (holds.isEmpty()) return refusal(ParentCarry.capacityProblem(carrier, target));
        double mouthGap = ContactGeometry.mouthGap(carrier, target), upright = ContactGeometry.braidGap(carrier, target, false);
        double low = ContactGeometry.braidGap(carrier, target, true), shortfall = Double.POSITIVE_INFINITY;
        // The hold that reaches first, in the carrier's order (a hatless Marisa's mouth before her braid tip).
        for (var candidate : holds) {
            if (candidate.reach() == Reach.MOUTH) {
                if (ContactGeometry.inMouthReach(carrier, target)) return chosen(candidate, false, mouthGap);
                shortfall = Math.min(shortfall, Math.max(0, mouthGap - ContactGeometry.GRAB_MARGIN));
            } else {
                if (Math.min(upright, low) <= 0) return chosen(candidate, upright > 0, Math.min(upright, low));
                shortfall = Math.min(shortfall, Math.min(upright, low));
            }
        }
        return Feasibility.of(Reason.NO_CONTACT, Double.isFinite(shortfall) ? shortfall : 0,
                String.format(Locale.ROOT, "holds=%s mouth_gap=%.2f braid_gap=%.2f", holds, mouthGap, Math.min(upright, low)));
    }

    private Feasibility chosen(Hold hold, boolean lean, double gap) {
        this.hold = hold;
        this.lean = lean;
        this.gap = gap;
        lift = hold.reach() == Reach.BRAID ? ClipMarkers.of(BRAID_LIFT).ticks("lift_end", LIFT_TICKS) : LIFT_TICKS;
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity carrier) {
        at = target.position();
        ticks = 0;
        startTick = carrier.level().getGameTime();
        turn = hold.reach() == Reach.BRAID ? new Face(ContactGeometry.braidYaw(carrier, target)) : null;
        if (turn != null) { turn.check(carrier); turn.start(carrier); }
        // The one record of the hold (and, on the brim, its spot) starts with the lift; the client's pose reads it.
        ParentCarry.plan(carrier, target, hold, lean);
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        double moved = target.position().distanceTo(at);
        if (moved > DRIFT) return Outcome.of(Reason.TARGET_MOVED, moved, String.format(Locale.ROOT, "moved=%.2f", moved));
        ticks++;
        // The braid side turns to the target while the braid reaches out; the lift ends once both are done.
        double angle = turn != null && turn.tick(carrier) instanceof Running turning ? turning.metric() : 0;
        if (angle > 0 || ticks < lift) return new Running(Math.max(0, lift - ticks) + angle, TravelMode.CONSUMED);
        // A child that went into water or under an operation meanwhile is not there to take: no retry of the same grab.
        if (!outOfWater && target.isInWaterOrBubble() || target.operations().active())
            return Outcome.of(Reason.TARGET_GONE, 0, target.isInWaterOrBubble() ? "target_in_water" : "target_operated");
        if (!Motor.mount(target, carrier, "grab")) return Outcome.of(Reason.NO_CONTACT, 0, "mount_refused");
        target.fallDistance = 0;
        return new Outcome.Done("mounted target=" + target.getUUID());
    }

    @Override public MotionPose pose() {
        return new MotionPose(hold.reach() == Reach.MOUTH ? "" : lean ? BRAID_LEAN : BRAID_LIFT, startTick, false);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "reach=%s hold=%s gap=%.2f lean=%b lift=%d target=%s", hold.reach().name().toLowerCase(Locale.ROOT),
                hold.key(), gap, lean, lift, target.getUUID());
    }

    @Override public String endDetail(Outcome outcome) { return "reach=" + hold.reach().name().toLowerCase(Locale.ROOT) + " hold=" + hold.key(); }

    /** The structural refusal vocabulary of {@code ParentCarry}, mapped onto the fixed outcome classes of spec 9.2 by its first
     *  hold's reason. */
    private static Feasibility refusal(String problem) {
        Reason reason = switch (problem.split("/")[0]) {
            case "dead", "level" -> Reason.TARGET_GONE;
            case "too_big", "stage", "carrier_kind", "pregnant", "not_baby" -> Reason.TOO_HEAVY;
            default -> Reason.NO_ROOM;
        };
        return Feasibility.of(reason, 0, problem);
    }
}
