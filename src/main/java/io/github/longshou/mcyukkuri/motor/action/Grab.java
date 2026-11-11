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
import io.github.longshou.mcyukkuri.motor.HeldMove;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Take a body into one of the carrier's holds: {@code ParentCarry.holdsFor} gives them and the Grab takes the first whose reach
 * gets it, then seats it as a real passenger. The carrier first turns at its turn rate (the mouth to the target, the braid side
 * squarely to it by {@code ParentCarry.braidTurn}). A braid-tip child is mounted once the braid has closed on it (lift_start) and the
 * Grab runs on to the clip's lift_end while the braid lifts it clear ({@code ParentCarry.braided}). A brim young is taken by the braid's
 * authored lift ({@code ParentCarry.BRIM_LIFT}): the carrier stands where the clip's grip lies on the young and turns so it does, the clip
 * plays, the young rides its tuft from lift_start and the Grab ends when the clip has ended and the young has walked to its brim spot.
 * A mouth hold stays mounted through its real lift to the lips before the next action begins.
 * A stick in the braid (or the mouth) this grip needs is first moved out of the way by one visible {@link Stow} beat.
 */
public final class Grab implements Action {
    public static final String ID = "grab";
    /** The mouth lift (ticks): the parent stands over the child and closes on it. */
    public static final int LIFT_TICKS = 14;
    /** How far (blocks) a target that drifted while the braid closed may lie past the braid's tip and still be gripped: a hand's breadth,
     *  well inside what the lift's own swing hides. */
    public static final double GRIP_SLACK = .05;
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
    private boolean mounted, fromHead, begun;
    private long startTick, clipStart;
    private Vec3 startStand, startTarget;
    private final StowStep clear = new StowStep();
    private Hop brimHop;

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

    /** A dry free target may be in an ordinary physics jump without an authored Hop. */
    public boolean awaitingLanding() { return awaitingLanding(Attach.of(target)); }

    private boolean awaitingLanding(AttachState attach) {
        return attach instanceof AttachState.Airborne
                || attach instanceof AttachState.Free free && !free.inWater() && !target.onGround()
                && !SurfaceApproach.stands(target, target.position());
    }

    /** Approach a stand that can actually see and reach this body with an available hold. */
    public Vec3 contactStand(YukkuriEntity carrier) {
        var holds = ParentCarry.holdsFor(carrier, target);
        // The brim lift takes the young off the ground at its clip's grip: the stand is where that grip lies on it.
        if (!holds.isEmpty() && holds.get(0) == Hold.BRIM)
            return SurfaceApproach.beside(carrier, target.position(), ParentCarry.brimReach(carrier), target,
                    at -> brimContact(carrier, at, ParentCarry.brimYaw(at, target.position())).ok());
        boolean mouth = holds.stream().anyMatch(h -> h.reach() == Reach.MOUTH);
        boolean braid = holds.stream().anyMatch(h -> h.reach() == Reach.BRAID);
        // Sixteen bearings round the target, not four: young already set down on the bank are obstacles to stand round, so a stand
        // another yukkuri occupies is no stand.
        return SurfaceApproach.beside(carrier, target.position(), (carrier.getBbWidth() + target.getBbWidth()) * .5 + .15, target,
                at -> Math.abs(at.y - target.getY()) <= ContactGeometry.GRAB_HEIGHT && free(carrier, at)
                        && (mouth && ContactGeometry.inMouthReach(carrier, at, target)
                        || braid && ContactGeometry.braidGap(carrier, at, target) <= 0));
    }

    /** Where the carrier would stand clear of the target and of every other yukkuri on the ground. */
    private boolean free(YukkuriEntity carrier, Vec3 at) {
        var body = carrier.getBoundingBox().move(at.subtract(carrier.position())).deflate(.005);
        return !body.intersects(target.getBoundingBox()) && carrier.level().getEntitiesOfClass(YukkuriEntity.class, body,
                other -> other != carrier && other != target && other.getVehicle() == null).isEmpty();
    }

    private Feasibility brimContact(YukkuriEntity carrier, Vec3 stand, float yaw) {
        double gap = ParentCarry.brimGap(carrier, stand, target);
        if (gap > GRIP_SLACK) return Feasibility.of(Reason.NO_CONTACT, gap, "brim_gap");
        if (!free(carrier, stand)) return Feasibility.of(Reason.NO_CONTACT, 0, "brim_stand_occupied");
        if (!ParentCarry.brimRouteClear(carrier, target, stand, yaw))
            return Feasibility.of(Reason.NO_SUPPORT, 0, "brim_lift_blocked");
        return Feasibility.pass();
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
        if (awaitingLanding(attach)) return Feasibility.of(Reason.NO_CONTACT, 0, "target_airborne");
        if (source != null && ContactGeometry.braided(carrier)) {
            String problem = ParentCarry.holdProblem(carrier, target, Hold.BRAID, false, source);
            if (problem != null) return refusal(problem);
            // The same reach the lift re-reads every tick (upright, else the low lean): a grip begun here is one that holds.
            double upright = ContactGeometry.braidGap(carrier, target, false), low = ContactGeometry.braidGap(carrier, target, true);
            if (Math.min(upright, low) > 0) {
                double shortfall = Math.min(upright, low);
                return Feasibility.of(Reason.NO_CONTACT, shortfall, String.format(Locale.ROOT, "braid_gap=%.2f", shortfall));
            }
            chosen(Hold.BRAID, upright > 0, Math.min(upright, low));
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
            if (candidate == Hold.BRIM) {
                // The brim comes first while it has room: a young off the braid's grip is stepped to (contactStand), not mouthed.
                var ready = brimContact(carrier, carrier.position(), ParentCarry.brimYaw(carrier.position(), target.position()));
                if (!ready.ok()) return ready;
                rejected = "mouth:" + mouthWhy;
                return chosen(candidate, false, ParentCarry.brimGap(carrier, carrier.position(), target));
            }
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
        if (hold == Hold.BRIM) {
            lift = BraidFetch.marker(ParentCarry.BRIM_LIFT, "lift_start");
            way = ClipMarkers.of(ParentCarry.BRIM_LIFT).length() - lift;
        } else if (hold.reach() == Reach.BRAID) {
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
        clipStart = -1;
        mounted = begun = false;
        startStand = carrier.position();
        startTarget = target.position();
        // The part that grips the child is cleared of her held stick first; the hold record starts only after it.
        if (clear.start(carrier, hold.reach() == Reach.BRAID ? Stow.freeBraid(carrier, "grab") : Stow.freeMouth(carrier, "grab"))) return;
        begin(carrier);
    }

    private void begin(YukkuriEntity carrier) {
        begun = true;
        startTick = carrier.level().getGameTime();
        // The one record of the hold (and, on the brim, its spot) starts with the lift; the client's pose reads it.
        ParentCarry.plan(carrier, target, hold, lean, source);
        facing = hold == Hold.BRIM ? ParentCarry.brimYaw(carrier.position(), target.position())
                : hold.reach() == Reach.BRAID ? ParentCarry.braidTurn(carrier, target)
                : (float) Math.toDegrees(Math.atan2(target.getZ() - carrier.getZ(), target.getX() - carrier.getX())) - 90;
        turn = new Face(facing);
        turn.check(carrier);
        turn.start(carrier);
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        if (!begun) {
            var cleared = clear.tick(carrier);
            if (cleared instanceof Running running) return new Running(running.metric() + lift + way, TravelMode.CONSUMED);
            if (cleared instanceof Outcome outcome && outcome.reason() != null) return outcome;
            if (!target.isAlive()) return Outcome.of(Reason.TARGET_GONE, 0, "target_gone");
            begin(carrier);
        }
        if (hold == Hold.BRIM) return brimTick(carrier);
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
        if (way > 0 && gap > GRIP_SLACK) {
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

    /** The brim lift: turn, the clip from its start, the grip at lift_start (the young must still lie on it), then the ride and walk. */
    private Progress brimTick(YukkuriEntity carrier) {
        if (mounted) {
            if (target.getVehicle() != carrier) return Outcome.of(Reason.DROPPED, 0, "let_go");
            if (ParentCarry.brimHopReady(carrier, target)) {
                boolean starting = brimHop == null;
                if (brimHop == null) {
                    brimHop = Hop.carried(carrier, ParentCarry.brimDestination(carrier, target));
                    var ready = brimHop.check(target);
                    if (!ready.ok()) {
                        ParentCarry.brimWalkInstead(carrier, target, ready.reason() + ":" + ready.detail());
                        brimHop = null;
                        return new Running(ParentCarry.placingLeft(carrier, target), TravelMode.CONSUMED, true);
                    }
                    brimHop.start(target);
                }
                var progress = brimHop.tick(target);
                if (progress instanceof Outcome outcome && !(outcome instanceof Outcome.Done) && target.getVehicle() == carrier) {
                    ParentCarry.brimWalkInstead(carrier, target, outcome.toString());
                    brimHop = null;
                    return new Running(ParentCarry.placingLeft(carrier, target), TravelMode.CONSUMED, true);
                }
                if (starting && progress instanceof Running running)
                    return new Running(running.metric(), running.travel(), true);
                if (!(progress instanceof Outcome.Done)) return progress;
            }
            int left = ParentCarry.placingLeft(carrier, target);
            return left > 0 ? new Running(left, TravelMode.CONSUMED) : completed(carrier);
        }
        if (!target.isAlive()) return Outcome.of(Reason.TARGET_GONE, 0, "target_gone");
        if (target.operations().active()) return Outcome.of(Reason.TARGET_GONE, 0, "target_operated");
        if (target.isPanicking() && !carrier.family().carry().protectiveGrip(target) || target.isOnFire() || target.isInWaterOrBubble())
            return new Outcome.Interrupted("brim_target_in_danger");
        // A jump queued on the admission tick may start before the first grip tick.
        if (clipStart < 0 && awaitingLanding()) {
            GameplayLog.limited(carrier, "action", "grab.landing", 200,
                    "brim grab waiting for landing child={} at={}", target.getUUID(), GameplayLog.at(target.position()));
            return new Running(lift + way + Math.abs(Mth.wrapDegrees(facing - carrier.bodyYaw())), TravelMode.CONSUMED);
        }
        gap = ParentCarry.brimGap(carrier, carrier.position(), target);
        if (gap > GRIP_SLACK) return Outcome.of(Reason.NO_CONTACT, gap, "brim_grip_out_of_reach stand=" + GameplayLog.at(carrier.position()));
        if (clipStart < 0) {
            var turning = turn.tick(carrier);
            if (turning instanceof Running progress) return new Running(progress.metric() + lift + way, TravelMode.CONSUMED);
            if (!(turning instanceof Outcome.Done)) return turning;
            var ready = brimContact(carrier, carrier.position(), carrier.bodyYaw());
            if (!ready.ok()) return Outcome.of(ready.reason(), ready.shortfall(), ready.detail());
            clipStart = carrier.level().getGameTime();
        }
        int t = (int) (carrier.level().getGameTime() - clipStart);
        if (t < lift) return new Running(lift + way - t, TravelMode.CONSUMED);
        if (!ParentCarry.brimLifted(carrier, target, clipStart)) return Outcome.of(Reason.NO_SUPPORT, 0, "brim_lift_blocked");
        if (target.getVehicle() != carrier && !Motor.mount(target, carrier, "grab")) return Outcome.of(Reason.NO_CONTACT, 0, "mount_refused");
        target.fallDistance = 0;
        mounted = true;
        ParentCarry.gripped(carrier, target, rejected, way);
        return new Running(ParentCarry.placingLeft(carrier, target), TravelMode.CONSUMED);
    }

    private Outcome completed(YukkuriEntity carrier) {
        ParentCarry.pickupCompleted(carrier, target);
        return new Outcome.Done("mounted target=" + target.getUUID());
    }

    @Override public void abort(YukkuriEntity carrier, String why) {
        if (brimHop != null && target.getVehicle() == carrier) brimHop.abort(target, why);
    }
    @Override public void finished(YukkuriEntity carrier, Outcome outcome) {
        if (!(outcome instanceof Outcome.Done) && brimHop != null && target.getVehicle() == carrier)
            brimHop.abort(target, outcome.label());
    }

    @Override public HeldMove heldMove() { return clear.move(); }

    @Override public MotionPose pose() {
        if (!begun) return MotionPose.NONE;
        if (fromHead) return new MotionPose("braid_head_take", startTick, false);
        if (hold == Hold.BRIM) return clipStart < 0 ? MotionPose.NONE : new MotionPose(ParentCarry.BRIM_LIFT, clipStart, false);
        if (hold.reach() == Reach.BRAID) {
            int contact = BraidFetch.marker(BraidFetch.REACH, "contact");
            return mounted ? new MotionPose(BraidFetch.HOLD, startTick + ticks - BraidFetch.marker(BraidFetch.HOLD, "grip"), false)
                    : new MotionPose(BraidFetch.REACH, startTick + Math.max(0, ticks - contact), false);
        }
        return new MotionPose("", startTick, false);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "reach=%s hold=%s gap=%.2f lean=%b lift=%d way=%d target=%s stand=%s targetPos=%s",
                hold.reach().name().toLowerCase(Locale.ROOT), hold.key(), gap, lean, lift, way, target.getUUID(),
                GameplayLog.at(startStand), GameplayLog.at(startTarget));
    }

    @Override public String endDetail(Outcome outcome) {
        return String.format(Locale.ROOT, "reach=%s hold=%s facing=%.1f target=%s targetPos=%s clipStarted=%b",
                hold.reach().name().toLowerCase(Locale.ROOT), hold.key(), facing, target.getUUID(), GameplayLog.at(target.position()), clipStart >= 0);
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
