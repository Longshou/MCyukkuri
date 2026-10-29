package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyDriver;
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
 * Take a body into the carrier's hold (spec 9.4) with whichever reach gets it: the mouth when it reaches (preferred), else
 * a Marisa's braid tip, upright or in the low lean. The carrier must be able to hold it in that reach at all
 * ({@code ParentCarry.holdProblem}), and it must not be in the air or in someone else's hold. The braid first turns its
 * side to the target; after the lift the target becomes a real passenger, and the vanilla passenger relation hangs it at
 * the reach ({@code ParentCarry.seat}). The braid reach is synced ({@link BodyDriver#BRAID}) for the client pose.
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
    private Reach reach = Reach.MOUTH;
    private boolean lean;
    private double gap;
    private Face turn;
    private Vec3 at;
    private int ticks, lift;
    private long startTick;

    public Grab(YukkuriEntity target) {
        if (target == null) throw new IllegalArgumentException("Grab needs a target");
        this.target = target;
    }

    @Override public String id() { return ID; }

    /** The body being lifted: until it is mounted, a host leaves it where it is. */
    public YukkuriEntity target() { return target; }

    /** The reach the last {@link #check} chose. */
    public Reach reach() { return reach; }

    @Override public Feasibility check(YukkuriEntity carrier) {
        if (!target.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_gone");
        if (target.level() != carrier.level()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_level");
        AttachState attach = Attach.of(target);
        if (attach instanceof AttachState.Held held && held.carrier() != carrier)
            return Feasibility.of(Reason.NO_ROOM, 0, "target_held");
        if (attach instanceof AttachState.Airborne) return Feasibility.of(Reason.NO_CONTACT, 0, "target_airborne");
        String mouth = ParentCarry.holdProblem(carrier, target, Reach.MOUTH, false);
        String braid = ParentCarry.holdProblem(carrier, target, Reach.BRAID, false);
        if (mouth != null && braid != null) return refusal(mouth, braid);
        double mouthGap = ContactGeometry.mouthGap(carrier, target), upright = ContactGeometry.braidGap(carrier, target, false);
        double low = ContactGeometry.braidGap(carrier, target, true);
        if (mouth == null && ContactGeometry.inMouthReach(carrier, target)) return chosen(Reach.MOUTH, false, mouthGap);
        if (braid == null && Math.min(upright, low) <= 0) return chosen(Reach.BRAID, upright > 0, Math.min(upright, low));
        double shortfall = Math.min(mouth == null ? Math.max(0, mouthGap - ContactGeometry.GRAB_MARGIN) : Double.POSITIVE_INFINITY,
                braid == null ? Math.min(upright, low) : Double.POSITIVE_INFINITY);
        return Feasibility.of(Reason.NO_CONTACT, Double.isFinite(shortfall) ? shortfall : 0,
                String.format(Locale.ROOT, "mouth_gap=%.2f braid_gap=%.2f", mouthGap, Math.min(upright, low)));
    }

    private Feasibility chosen(Reach reach, boolean lean, double gap) {
        this.reach = reach;
        this.lean = lean;
        this.gap = gap;
        lift = reach == Reach.BRAID ? ClipMarkers.of(BRAID_LIFT).ticks("lift_end", LIFT_TICKS) : LIFT_TICKS;
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity carrier) {
        at = target.position();
        ticks = 0;
        startTick = carrier.level().getGameTime();
        boolean braid = reach == Reach.BRAID;
        turn = braid ? new Face(ContactGeometry.braidYaw(carrier, target)) : null;
        if (turn != null) { turn.check(carrier); turn.start(carrier); }
        // The braid takes the target; a mouth grab clears a braid slot that holds nobody now (the target among them).
        if (braid) carrier.motor().braid(target, lean);
        else if (ParentCarry.braidRider(carrier) == null) carrier.motor().braid(null, false);
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        double moved = target.position().distanceTo(at);
        if (moved > DRIFT) return Outcome.of(Reason.TARGET_MOVED, moved, String.format(Locale.ROOT, "moved=%.2f", moved));
        ticks++;
        // The braid side turns to the target while the braid reaches out; the lift ends once both are done.
        double angle = turn != null && turn.tick(carrier) instanceof Running turning ? turning.metric() : 0;
        if (angle > 0 || ticks < lift) return new Running(Math.max(0, lift - ticks) + angle, TravelMode.CONSUMED);
        if (!Motor.mount(target, carrier, "grab")) return Outcome.of(Reason.NO_CONTACT, 0, "mount_refused");
        target.fallDistance = 0;
        return new Outcome.Done("mounted target=" + target.getUUID());
    }

    @Override public MotionPose pose() {
        return new MotionPose(reach == Reach.MOUTH ? "" : lean ? BRAID_LEAN : BRAID_LIFT, startTick, false);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "reach=%s gap=%.2f lean=%b lift=%d target=%s", reach.name().toLowerCase(Locale.ROOT),
                gap, lean, lift, target.getUUID());
    }

    @Override public String endDetail(Outcome outcome) { return "reach=" + reach.name().toLowerCase(Locale.ROOT); }

    /** The structural refusal vocabulary of {@code ParentCarry}, mapped onto the fixed outcome classes of spec 9.2 by the mouth's
     *  reason (the braid's stands beside it). */
    private static Feasibility refusal(String mouth, String braid) {
        Reason reason = switch (mouth) {
            case "dead", "level" -> Reason.TARGET_GONE;
            case "too_big", "stage", "carrier_kind", "pregnant", "not_baby" -> Reason.TOO_HEAVY;
            default -> Reason.NO_ROOM;
        };
        return Feasibility.of(reason, 0, mouth.equals(braid) ? mouth : "mouth=" + mouth + " braid=" + braid);
    }
}
