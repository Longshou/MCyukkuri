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
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * Take a body into one of the carrier's holds: {@code ParentCarry.holdsFor} gives them and the Grab takes the first whose reach
 * gets it, then seats it as a real passenger. The carrier first turns at its turn rate only as far as the hold needs (the mouth
 * to the target, the braid side by {@code ParentCarry.braidTurn}). A braid-tip child is mounted once the braid has closed on it
 * (lift_start) and the Grab runs on to the clip's place marker while the braid lifts it, swings it aside and sets it down beside
 * the carrier ({@code ParentCarry.braided}); the carrier never turns away with it.
 */
public final class Grab implements Action {
    public static final String ID = "grab";
    /** The mouth lift (ticks): the parent stands over the child and closes on it. */
    public static final int LIFT_TICKS = 14;
    /** The braid's clips: it reaches, closes on the child by {@code lift_start}, lifts it by {@code lift_end} and sets it down by
     *  {@code place}; the low lean reaches lower and farther. */
    public static final String BRAID_LIFT = "braid_cradle_lift", BRAID_LEAN = "braid_reach_low";
    /** How far the target may drift during the lift before the grab gives up (blocks). */
    private static final double DRIFT = .3;

    private final YukkuriEntity target;
    private Hold hold = Hold.MOUTH;
    private boolean lean;
    /** The yaw the carrier turns to before the lift (worked out at start). */
    private float facing;
    private double gap;
    private Face turn;
    private Vec3 at;
    /** A lift out of the water (a rescue): a target in the water is taken; any other grab ends when the target is in the water. */
    private final boolean outOfWater;
    private int ticks, lift, way, held;
    private boolean mounted;
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

    /** It lifts its target out of the water (a rescue). */
    public boolean outOfWater() { return outOfWater; }

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
        // A body lifted off a host is taken by the braid when it reaches: it comes up over the brim from above.
        if (attach instanceof AttachState.OnHost) holds = holds.stream().sorted(Comparator.comparing(h -> h.reach() != Reach.BRAID)).toList();
        double mouthGap = ContactGeometry.mouthGap(carrier, target), upright = ContactGeometry.braidGap(carrier, target, false);
        double low = ContactGeometry.braidGap(carrier, target, true), shortfall = Double.POSITIVE_INFINITY;
        boolean noWay = false;
        // The hold that reaches first, in the carrier's order (a hatless Marisa's mouth before her braid tip).
        for (var candidate : holds) {
            if (candidate.reach() == Reach.MOUTH) {
                if (ContactGeometry.inMouthReach(carrier, target)) return chosen(candidate, false, mouthGap);
                shortfall = Math.min(shortfall, Math.max(0, mouthGap - ContactGeometry.GRAB_MARGIN));
            } else if (Math.min(upright, low) <= 0) {
                // A braid-tip child off a host needs a way out to the ground beside the carrier; else the next hold.
                if (candidate != Hold.BRAID || ParentCarry.braidCanSetDown(carrier, target, upright > 0))
                    return chosen(candidate, upright > 0, Math.min(upright, low));
                noWay = true;
            } else shortfall = Math.min(shortfall, Math.min(upright, low));
        }
        if (noWay) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_braid_set_down");
        return Feasibility.of(Reason.NO_CONTACT, Double.isFinite(shortfall) ? shortfall : 0,
                String.format(Locale.ROOT, "holds=%s mouth_gap=%.2f braid_gap=%.2f", holds, mouthGap, Math.min(upright, low)));
    }

    private Feasibility chosen(Hold hold, boolean lean, double gap) {
        this.hold = hold;
        this.lean = lean;
        this.gap = gap;
        var clip = ClipMarkers.of(BRAID_LIFT);
        boolean tip = hold == Hold.BRAID;
        lift = hold.reach() != Reach.BRAID ? LIFT_TICKS : clip.ticks(tip ? "lift_start" : "lift_end", LIFT_TICKS);
        way = tip ? Math.max(0, clip.ticks("place", lift) - lift) : 0;
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity carrier) {
        at = target.position();
        ticks = held = 0;
        mounted = false;
        startTick = carrier.level().getGameTime();
        // The one record of the hold (and, on the brim, its spot) starts with the lift; the client's pose reads it.
        ParentCarry.plan(carrier, target, hold, lean);
        facing = hold.reach() == Reach.BRAID ? ParentCarry.braidTurn(carrier, target, lean)
                : (float) Math.toDegrees(Math.atan2(target.getZ() - carrier.getZ(), target.getX() - carrier.getX())) - 90;
        turn = new Face(facing);
        turn.check(carrier);
        turn.start(carrier);
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        // Held, a braid-tip child is on its way out with the braid: the Grab ends once it is set down beside the carrier.
        if (mounted) return target.getVehicle() != carrier ? Outcome.of(Reason.DROPPED, 0, "let_go")
                : ++held < way ? new Running(way - held, TravelMode.CONSUMED) : new Outcome.Done("mounted target=" + target.getUUID());
        double moved = target.position().distanceTo(at);
        if (moved > DRIFT) return Outcome.of(Reason.TARGET_MOVED, moved, String.format(Locale.ROOT, "moved=%.2f", moved));
        ticks++;
        // The carrier turns while the braid (or the mouth) reaches out; the lift ends once both are done.
        double angle = turn.tick(carrier) instanceof Running turning ? turning.metric() : 0;
        if (angle > 0 || ticks < lift) return new Running(Math.max(0, lift - ticks) + angle + way, TravelMode.CONSUMED);
        // A child that went into water or under an operation meanwhile is not there to take: no retry of the same grab.
        if (!outOfWater && target.isInWaterOrBubble() || target.operations().active())
            return Outcome.of(Reason.TARGET_GONE, 0, target.isInWaterOrBubble() ? "target_in_water" : "target_operated");
        // The world may have changed since check: with no way out left the braid lets go of it before lifting.
        if (way > 0 && !ParentCarry.braided(carrier, target, lean)) return Outcome.of(Reason.NO_SUPPORT, 0, "no_braid_set_down");
        if (!Motor.mount(target, carrier, "grab")) return Outcome.of(Reason.NO_CONTACT, 0, "mount_refused");
        target.fallDistance = 0;
        mounted = true;
        return way > 0 ? new Running(way, TravelMode.CONSUMED) : new Outcome.Done("mounted target=" + target.getUUID());
    }

    @Override public MotionPose pose() {
        return new MotionPose(hold.reach() == Reach.MOUTH ? "" : lean ? BRAID_LEAN : BRAID_LIFT, startTick, false);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "reach=%s hold=%s gap=%.2f lean=%b lift=%d way=%d target=%s", hold.reach().name().toLowerCase(Locale.ROOT),
                hold.key(), gap, lean, lift, way, target.getUUID());
    }

    @Override public String endDetail(Outcome outcome) {
        return String.format(Locale.ROOT, "reach=%s hold=%s facing=%.1f", hold.reach().name().toLowerCase(Locale.ROOT), hold.key(), facing);
    }

    /** The structural refusal vocabulary of {@code ParentCarry}, mapped onto the fixed outcome classes by its first word. */
    private static Feasibility refusal(String problem) {
        Reason reason = switch (problem.split("/")[0]) {
            case "dead", "level" -> Reason.TARGET_GONE;
            case "too_big", "stage", "carrier_kind", "pregnant", "not_baby" -> Reason.TOO_HEAVY;
            default -> Reason.NO_ROOM;
        };
        return Feasibility.of(reason, 0, problem);
    }
}
