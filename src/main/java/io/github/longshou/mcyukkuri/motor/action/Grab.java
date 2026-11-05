package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.family.ParentCarry.Hold;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
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
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * Take a body into one of the carrier's holds: {@code ParentCarry.holdsFor} gives them and the Grab takes the first whose reach
 * gets it, then seats it as a real passenger. The carrier first turns at its turn rate (the mouth to the target, the braid side
 * squarely to it by {@code ParentCarry.braidTurn}). A brim or braid-tip child is mounted once the braid has closed on it
 * (lift_start) and the Grab runs on to the clip's place marker (and a far-side brim child's own walk round the brim) while the braid lifts it clear into its hold
 * ({@code ParentCarry.braided}). A mouth hold stays mounted through its real lift to the lips before the next action begins.
 */
public final class Grab implements Action {
    public static final String ID = "grab";
    /** The mouth lift (ticks): the parent stands over the child and closes on it. */
    public static final int LIFT_TICKS = 14;
    /** The braid's clips: it reaches, closes on the child by {@code lift_start}, lifts it by {@code lift_end} and sets it down by
     *  {@code place}; the low lean reaches lower and farther. */
    public static final String BRAID_LIFT = "braid_cradle_lift", BRAID_LEAN = "braid_reach_low";
    private final YukkuriEntity target;
    private Hold hold = Hold.MOUTH;
    private boolean lean;
    /** The yaw the carrier turns to before the lift (worked out at start). */
    private float facing;
    private double gap;
    private String rejected;
    private Face turn;
    private final SurfaceHost source;
    private int ticks, lift, way, held;
    private boolean mounted, fromHead;
    private long startTick;

    public Grab(YukkuriEntity target) { this(target, null); }
    public Grab(YukkuriEntity target, SurfaceHost source) {
        if (target == null) throw new IllegalArgumentException("Grab needs a target");
        this.target = target;
        this.source = source;
    }

    @Override public String id() { return ID; }

    /** The body being lifted: until it is mounted, a host leaves it where it is. */
    public YukkuriEntity target() { return target; }

    @Override public SurfaceHost host() { return source; }

    /** The hold the last {@link #check} chose (its reach is {@code hold().reach()}). */
    public Hold hold() { return hold; }

    /** Approach a stand that can actually see and reach this body with an available hold. */
    public Vec3 contactStand(YukkuriEntity carrier) {
        var holds = ParentCarry.holdsFor(carrier, target);
        boolean mouth = holds.stream().anyMatch(h -> h.reach() == Reach.MOUTH);
        boolean braid = holds.stream().anyMatch(h -> h.reach() == Reach.BRAID);
        return SurfaceApproach.beside(carrier, target, (carrier.getBbWidth() + target.getBbWidth()) * .5 + .15,
                ContactGeometry.GRAB_HEIGHT, Double.POSITIVE_INFINITY,
                at -> mouth && ContactGeometry.inMouthReach(carrier, at, target)
                        || braid && ContactGeometry.braidGap(carrier, at, target) <= 0);
    }

    @Override public Feasibility check(YukkuriEntity carrier) {
        if (!target.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_gone");
        if (target.level() != carrier.level()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_level");
        AttachState attach = Attach.of(target);
        fromHead = target.getVehicle() == carrier && ParentCarry.hold(carrier, target) == Hold.HEAD;
        if (fromHead) {
            String problem = ParentCarry.holdProblem(carrier, target, Hold.BRAID, false, ParentCarry.carryHost(carrier, target));
            if (problem != null) return refusal(problem);
            chosen(Hold.BRAID, false, ContactGeometry.braidGap(carrier, target, false));
            lift = BraidFetch.marker("braid_head_take", "lift_start");
            way = ClipMarkers.of("braid_head_take").length() - lift;
            return Feasibility.pass();
        }
        if (attach instanceof AttachState.Held held)
            return Feasibility.of(Reason.NO_ROOM, 0, held.carrier() == carrier ? "already_held" : "target_held");
        if (attach instanceof AttachState.Airborne) return Feasibility.of(Reason.NO_CONTACT, 0, "target_airborne");
        if (source != null && ContactGeometry.braided(carrier)) {
            String problem = ParentCarry.holdProblem(carrier, target, Hold.BRAID, false, source);
            if (problem != null) return refusal(problem);
            // The same reach the lift itself re-reads every tick until the braid closes: a grip begun here is one that holds.
            double upright = ContactGeometry.braidGap(carrier, target, false);
            if (upright > 0) return Feasibility.of(Reason.NO_CONTACT, upright, String.format(Locale.ROOT, "braid_gap=%.2f", upright));
            chosen(Hold.BRAID, false, upright);
            rejected = "braid_lift";
            return Feasibility.pass();
        }
        var holds = ParentCarry.holdsFor(carrier, target);
        if (holds.isEmpty()) return refusal(ParentCarry.capacityProblem(carrier, target));
        double mouthGap = ContactGeometry.mouthGap(carrier, target), upright = ContactGeometry.braidGap(carrier, target, false);
        double low = ContactGeometry.braidGap(carrier, target, true);
        boolean mouth = ContactGeometry.inMouthReach(carrier, target), braid = Math.min(upright, low) <= 0;
        String mouthWhy = holds.stream().noneMatch(h -> h.reach() == Reach.MOUTH) ? "no_capacity" : mouth ? "lower_preference" : "out_of_reach_or_sight";
        String braidWhy = holds.stream().noneMatch(h -> h.reach() == Reach.BRAID) ? "no_capacity" : !braid ? "out_of_reach_or_sight" : "lower_preference";
        // Capacity and preference come from holdsFor; take the first hold whose reach and route work.
        for (var candidate : holds) {
            if (candidate.reach() == Reach.MOUTH && mouth) {
                rejected = "braid:" + braidWhy;
                return chosen(candidate, false, mouthGap);
            }
            if (candidate.reach() == Reach.BRAID && braid) {
                rejected = "mouth:" + mouthWhy;
                return chosen(candidate, upright > 0, Math.min(upright, low));
            }
        }
        double shortfall = Math.min(holds.stream().anyMatch(h -> h.reach() == Reach.MOUTH)
                ? Math.max(0, mouthGap - ContactGeometry.GRAB_MARGIN) : Double.POSITIVE_INFINITY,
                holds.stream().anyMatch(h -> h.reach() == Reach.BRAID) ? Math.min(upright, low) : Double.POSITIVE_INFINITY);
        return Feasibility.of(Reason.NO_CONTACT, Double.isFinite(shortfall) ? shortfall : 0,
                String.format(Locale.ROOT, "holds=%s mouth_gap=%.2f braid_gap=%.2f", holds, mouthGap, Math.min(upright, low)));
    }

    private Feasibility chosen(Hold hold, boolean lean, double gap) {
        this.hold = hold;
        this.lean = lean;
        this.gap = gap;
        // A brim placement and a held braid pickup share the reach and hold clips.
        if (hold.reach() == Reach.BRAID) {
            lift = BraidFetch.marker(BraidFetch.REACH, "contact") + BraidFetch.marker(BraidFetch.HOLD, "grip");
            way = BraidFetch.marker(BraidFetch.HOLD, hold == Hold.BRAID ? "lift_end" : "place") - BraidFetch.marker(BraidFetch.HOLD, "grip");
        } else {
            lift = LIFT_TICKS;
            way = 0;
        }
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity carrier) {
        ticks = held = 0;
        mounted = false;
        startTick = carrier.level().getGameTime();
        // The one record of the hold (and, on the brim, its spot) starts with the lift; the client's pose reads it.
        ParentCarry.plan(carrier, target, hold, lean, source);
        facing = hold.reach() == Reach.BRAID ? ParentCarry.braidTurn(carrier, target)
                : (float) Math.toDegrees(Math.atan2(target.getZ() - carrier.getZ(), target.getX() - carrier.getX())) - 90;
        turn = new Face(facing);
        turn.check(carrier);
        turn.start(carrier);
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        if (mounted) {
            if (target.getVehicle() != carrier) return Outcome.of(Reason.DROPPED, 0, "let_go");
            double remaining = Math.max(Math.max(way - ++held, ParentCarry.placingLeft(carrier, target)),
                    target.position().distanceTo(ParentCarry.rawSeat(carrier, target, 0)));
            return remaining > .02 ? new Running(remaining, TravelMode.CONSUMED) : completed(carrier);
        }
        ticks++;
        if (!target.isAlive()) return Outcome.of(Reason.TARGET_GONE, 0, "target_gone");
        if (hold.reach() == Reach.BRAID) {
            facing = ParentCarry.braidTurn(carrier, target);
            turn = new Face(facing);
            turn.start(carrier);
        }
        // The carrier turns while the braid (or the mouth) reaches out; the lift ends once both are done.
        double angle = turn.tick(carrier) instanceof Running turning ? turning.metric() : 0;
        if (angle > 0 || ticks < lift) return new Running(Math.max(0, lift - ticks) + angle + way, TravelMode.CONSUMED);
        if (target.operations().active()) return Outcome.of(Reason.TARGET_GONE, 0, "target_operated");
        if (hold.reach() == Reach.MOUTH && !ContactGeometry.inMouthReach(carrier, target))
            return Outcome.of(Reason.NO_CONTACT, ContactGeometry.mouthGap(carrier, target), "mouth_grip_out_of_reach");
        if (way > 0) {
            lean = ContactGeometry.braidGap(carrier, target, false) > 0;
            gap = ContactGeometry.braidGap(carrier, target, lean);
        }
        if (way > 0 && gap > 0) {
            int left = lift + ClipMarkers.of(BraidFetch.REACH).length() - ticks;
            if (left > 0) return new Running(left + way, TravelMode.CONSUMED);
            return Outcome.of(Reason.NO_CONTACT, gap, "braid_grip_out_of_reach");
        }
        if (way > 0 && !ParentCarry.braided(carrier, target, lean)) return Outcome.of(Reason.NO_SUPPORT, 0, "braid_lift_blocked");
        if (hold == Hold.MOUTH && (way = ParentCarry.mouthLift(carrier, target)) < 0) return Outcome.of(Reason.NO_ROOM, 0, "mouth_lift_blocked");
        if (target.getVehicle() != carrier && !Motor.mount(target, carrier, "grab")) return Outcome.of(Reason.NO_CONTACT, 0, "mount_refused");
        target.fallDistance = 0;
        mounted = true;
        ParentCarry.gripped(carrier, target, rejected, way);
        return way > 0 ? new Running(way, TravelMode.CONSUMED) : completed(carrier);
    }

    private Outcome completed(YukkuriEntity carrier) {
        ParentCarry.pickupCompleted(carrier, target);
        return new Outcome.Done("mounted target=" + target.getUUID());
    }

    @Override public MotionPose pose() {
        if (fromHead) return new MotionPose("braid_head_take", startTick, false);
        if (hold.reach() == Reach.BRAID) {
            int contact = BraidFetch.marker(BraidFetch.REACH, "contact");
            return mounted ? new MotionPose(BraidFetch.HOLD, startTick + ticks - BraidFetch.marker(BraidFetch.HOLD, "grip"), false)
                    : new MotionPose(BraidFetch.REACH, startTick + Math.max(0, ticks - contact), false);
        }
        return new MotionPose("", startTick, false);
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
            case "too_big", "stage", "carrier_kind", "pregnant" -> Reason.TOO_HEAVY;
            default -> Reason.NO_ROOM;
        };
        return Feasibility.of(reason, 0, problem);
    }
}
