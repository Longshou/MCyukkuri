package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.family.ParentCarry.Hold;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry.Reach;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.HeldMove;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.CarryGeometry;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Turn a mouth hold to clear ground if needed, lower the child, then dismount at its measured feet. A braid that lowers a brim or
 *  head child first moves her held stick out of the way ({@link Stow}); once the braid is free again it takes the stick back. */
public final class Release implements Action {
    public static final String ID = "release";
    /** How far below the let-go feet support or water has to exist (blocks). */
    private static final double DROP = 1.5;

    private final YukkuriEntity child;
    private YukkuriEntity carrier;
    private float facing;
    private Face turn;
    private Walk approach;
    private final SurfaceHost destination;
    private SeatTarget seat;
    private boolean released;
    private BlockPos nest;
    private Vec3 endpoint;
    private String placement;
    private Outcome outcome;
    /** Route plans one set-down search may spend on refused stands; the spots are tried nearest first. */
    private static final int STAND_TRIES = 6;
    private int failedWorld, rejectedSpace, rejectedStand, settling;
    private long startTick;
    private Vec3 previousLocal;
    private double lowered;
    private final StowStep stow = new StowStep();
    private boolean begun;

    public Release(YukkuriEntity child) { this(child, null); }
    public Release(YukkuriEntity child, SurfaceHost destination) {
        this.child = child;
        this.destination = destination;
    }

    public static Release atNest(YukkuriEntity child, BlockPos nest) {
        var release = new Release(child);
        release.nest = nest;
        return release;
    }

    @Override public String id() { return ID; }
    @Override public SurfaceHost host() { return destination; }
    @Override public String startDetail() {
        return "child=" + child.getUUID() + (seat == null ? "" : " place=" + seat.kind() + " local=" + seat.local() + " facing=" + facing);
    }

    /** The body being set down. */
    public YukkuriEntity child() { return child; }
    /** Observed successful set-down, never merely a planned release or an already-free child (and the braid's taking her stick
     *  back after it has finished). */
    public boolean released() { return released && !stow.running(); }

    @Override public Feasibility check(YukkuriEntity carrier) {
        if (!child.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "child_gone");
        if (child.getVehicle() != carrier) return Feasibility.of(Reason.NO_CONTACT, 0, "not_held");
        if (destination != null) {
            var places = destination.places(child, Integer.MAX_VALUE);
            GameplayLog.limited(carrier, "family", "release.places", 200,
                    "release places child={} host={} cabin={} head={} brim={}", child.getUUID(), destination.entity().getUUID(),
                    places.stream().filter(p -> p.kind() == SeatTarget.Kind.CABIN).count(),
                    places.stream().filter(p -> p.kind() == SeatTarget.Kind.HEAD).count(),
                    places.stream().filter(p -> p.kind() == SeatTarget.Kind.BRIM).count());
            // A parent's own choice keeps the hull within its capacity (a young that boards itself is not weighed this way).
            String full = destination.loadRefusal(carrier, child);
            if (places.isEmpty() || full != null) return Feasibility.of(Reason.NO_ROOM, 0, full == null ? "no_seat" : "hull_full " + full);
            seat = nearest(carrier, places, facings(carrier));
            return seat != null ? Feasibility.pass() : Feasibility.of(Reason.NO_SUPPORT, 0, "transfer_blocked");
        }
        return plan(carrier, false);
    }

    private List<Float> facings(YukkuriEntity carrier) {
        var facings = new ArrayList<Float>();
        facings.add(carrier.bodyYaw());
        if (carrier.getVehicle() == destination.entity()) {
            var spot = destination.riders().spot(carrier.getUUID());
            for (int direction : new int[]{1, -1}) for (int angle = 5; angle <= 180; angle += 5) {
                float yaw = spot.yaw() + direction * angle;
                if (Floor.turnHeight(destination, carrier, spot.local(), spot.yaw(), yaw).isEmpty()) break;
                facings.add(carrier.bodyYaw() + direction * angle);
            }
        }
        facings.sort(Comparator.comparingDouble(yaw -> Math.abs(Mth.wrapDegrees(yaw - carrier.bodyYaw()))));
        return facings;
    }

    private SeatTarget nearest(YukkuriEntity carrier, List<SeatTarget> places, List<Float> facings) {
        for (var target : places.stream().sorted(Comparator.comparing(SeatTarget::kind)
                .thenComparingDouble(p -> p.kind() == SeatTarget.Kind.BRIM ? 0 : p.world().distanceToSqr(child.position()))).toList()) {
            if (target.unavailable(child) != null) continue;
            var grip = target.world().add(0, ParentCarry.GRIP_Y * child.growthScale() * child.getScale() / 16, 0);
            // The braid side turned to the place first (user 2026-11-05), so the braid never sets a child down across her body.
            float side = ContactGeometry.braidYaw(carrier, target.world());
            var order = new ArrayList<>(facings);
            if (carrier.getVehicle() != destination.entity()) order.add(side);
            order.sort(Comparator.comparingDouble(yaw -> Math.abs(Mth.wrapDegrees(yaw - side))));
            for (float yaw : order) if (ParentCarry.transferGap(carrier, grip, yaw) <= 0
                    && keepsPlace(carrier, target, yaw)
                    && ParentCarry.transferClear(carrier, child, target, yaw)) {
                facing = yaw;
                return target;
            }
        }
        return null;
    }

    private boolean keepsPlace(YukkuriEntity carrier, SeatTarget target, float yaw) {
        if (target.kind() != SeatTarget.Kind.CABIN || carrier.getVehicle() != destination.entity()) return true;
        var frame = destination.frame();
        var at = frame.toLocal(carrier.position());
        var body = BodyFootprint.envelope(carrier.assetId(), carrier.getBbWidth() / frame.unit(),
                carrier.getBbHeight() / frame.unit(), Math.toRadians(yaw - destination.entity().getYRot()));
        double scale = child.growthScale() * child.getScale() / 16 / frame.unit();
        double bottom = target.local().y + HatGeometry.anchor().y * scale;
        var hat = new Disc(target.local().x, target.local().z, ParentCarry.headwearRadius(child) / frame.unit(), bottom, bottom + .3 * scale);
        return !body.overlaps(at.x, at.y, at.z, hat, .3 / HullBounds.SCALE);
    }

    /** One endpoint/stand planner for cushions and nearby ground; execution uses the same room and turn tests. */
    private Feasibility plan(YukkuriEntity carrier, boolean outside) {
        var bed = BedContainer.of(child, nest);
        for (int pass = outside || bed == null ? 1 : 0; pass < 2; pass++) {
            int stands = 0;
            var spots = new ArrayList<Vec3>();
            double half = pass == 0 ? ((FloorShape.RoundedRect) bed.floor()).half() : carrier.getBbWidth() * 2;
            double step = pass == 0 ? child.getBbWidth() / 2 : carrier.getBbWidth() / 2;
            var centre = pass == 0 ? Vec3.ZERO.subtract(bed.toPlane(Vec3.ZERO)) : carrier.position();
            if (pass == 1) spots.add(ParentCarry.groundPoint(carrier, child, carrier.bodyYaw()));
            for (double x = -half; x <= half; x += step) for (double z = -half; z <= half; z += step)
                if (pass == 1 || bed.standsInside(x, z, child.getBbWidth()))
                    spots.add(new Vec3(centre.x + x, pass == 0 ? bed.floorY() : centre.y, centre.z + z));
            spots.sort(Comparator.comparingDouble(carrier::distanceToSqr));
            int free = 0;
            for (var point : spots) for (double down = 0; down <= (pass == 0 ? 0 : DROP); down += .5) {
                if (stands >= STAND_TRIES) break;
                var at = pass == 0 ? point : SurfaceApproach.restingFloor(child, point.add(0, .1 - down, 0));
                if (at == null || !free(carrier, at)) { rejectedSpace++; continue; }
                if (pass == 1 && bed != null && bed.floor().contains(bed.toPlane(at).x, bed.toPlane(at).z)) continue;
                free++;
                if (place(carrier, at)) { if (pass == 0) placement = "nest"; return Feasibility.pass(); }
                rejectedStand++;
                stands++;
            }
            if (pass == 0) placement = free == 0 ? "nest_full" : "nest_unreachable";
        }
        return Feasibility.of(Reason.NO_ROOM, 0, "no_supported_release_spot space=" + rejectedSpace + " stand=" + rejectedStand);
    }

    /** An occupied destination or blocked approach ends the nest route; take a different, supported outside release. */
    private Progress outsideAfter(YukkuriEntity carrier, Outcome refusal) {
        if (!"nest".equals(placement)) return refusal;
        if (approach != null) approach.abort(carrier, "nest_release_blocked");
        approach = null;
        turn = null;
        placement = "nest_blocked";
        var outside = plan(carrier, true);
        GameplayLog.event(carrier, "family", "carry nest release blocked child={} reason={} detail={} outside={}",
                child.getUUID(), refusal.reason(), refusal, outside.ok() ? endpoint : outside.detail());
        if (!outside.ok()) return Outcome.of(outside.reason(), outside.shortfall(), outside.detail());
        start(carrier);
        return new Running(3 * 360, TravelMode.CONSUMED, true);
    }

    private float facing(YukkuriEntity carrier, Vec3 stand, Vec3 at) {
        var offset = ParentCarry.groundPoint(carrier, child, 0).subtract(carrier.position());
        return SurfaceApproach.yaw(stand, at) - SurfaceApproach.yaw(Vec3.ZERO, offset);
    }

    private boolean place(YukkuriEntity carrier, Vec3 at) {
        double distance = ParentCarry.groundPoint(carrier, child, 0).subtract(carrier.position()).horizontalDistance() + .03;
        var stand = carrier.position();
        if (!SurfaceApproach.supportedGround(carrier, stand) || stand.subtract(at).horizontalDistance() > distance
                || !room(carrier, stand, facing(carrier, stand, at), at).ok())
            stand = SurfaceApproach.beside(carrier, at, distance, candidate ->
                    !child.getDimensions(child.getPose()).makeBoundingBox(at).inflate(.025, 0, .025)
                            .intersects(carrier.getBoundingBox().move(candidate.subtract(carrier.position())))
                            && room(carrier, candidate, facing(carrier, candidate, at), at).ok());
        if (stand == null) return false;
        endpoint = at;
        facing = facing(carrier, stand, at);
        approach = stand.equals(carrier.position()) ? null : new Walk(stand);
        return true;
    }

    private boolean free(YukkuriEntity carrier, Vec3 at) {
        return SurfaceApproach.supportedGround(child, at)
                && carrier.level().getEntitiesOfClass(YukkuriEntity.class, child.getDimensions(child.getPose()).makeBoundingBox(at).deflate(.005),
                    other -> other != child && other != carrier && !carrier.hasPassenger(other)).isEmpty();
    }

    private Feasibility room(YukkuriEntity carrier, Vec3 stand, float yaw, Vec3 at) {
        if (!free(carrier, at)) return Feasibility.of(Reason.NO_ROOM, 0, "no_supported_body_space");
        if (child.getDimensions(child.getPose()).makeBoundingBox(at).intersects(carrier.getBoundingBox().move(stand.subtract(carrier.position()))))
            return Feasibility.of(Reason.NO_ROOM, 0, "carrier_body");
        if (ParentCarry.hold(carrier, child) == Hold.MOUTH) {
            var host = ParentCarry.carryHost(carrier, child);
            if (host != null && carrier.getVehicle() != host.entity() && ParentCarry.pinned(carrier, child, at) > at.y)
                return Feasibility.of(Reason.NO_ROOM, 0, "carry_host");
        }
        if (!clearTurn(carrier, stand, yaw)) return Feasibility.of(Reason.NO_ROOM, 0, "mouth_turn_blocked");
        if (ParentCarry.hold(carrier, child) == Hold.BRIM) {
            var grip = at.add(0, ParentCarry.GRIP_Y * child.growthScale() * child.getScale() / 16, 0);
            double gap = Math.min(ContactGeometry.braidGapFrom(carrier, stand, grip, false),
                    ContactGeometry.braidGapFrom(carrier, stand, grip, true));
            if (gap > 0) return Feasibility.of(Reason.NO_CONTACT, gap, "braid_release_out_of_reach");
        }
        if ((ParentCarry.hold(carrier, child) == Hold.BRIM || ParentCarry.hold(carrier, child) == Hold.HEAD)
                && !ParentCarry.clearLowering(carrier, child, stand, yaw, at))
            return Feasibility.of(Reason.NO_ROOM, 0, "lowering_route_blocked");
        return Feasibility.pass();
    }

    private boolean clearTurn(YukkuriEntity carrier, Vec3 stand, float yaw) {
        if (ParentCarry.hold(carrier, child) != Hold.MOUTH) return true;
        float span = Mth.wrapDegrees(yaw - carrier.bodyYaw());
        for (int i = 0, steps = Math.max(1, Mth.ceil(Math.abs(span) / 5)); i <= steps; i++) {
            var at = ParentCarry.mouthAt(carrier, child, carrier.bodyYaw() + span * i / steps).add(stand.subtract(carrier.position()));
            if (!child.level().noCollision(child, child.getDimensions(child.getPose()).makeBoundingBox(at))) return false;
        }
        return true;
    }

    @Override public void start(YukkuriEntity carrier) {
        this.carrier = carrier;
        begun = false;
        var hold = ParentCarry.hold(carrier, child);
        if ((hold == Hold.BRIM || hold == Hold.HEAD) && stow.start(carrier, Stow.freeBraid(carrier, "release"))) return;
        begin(carrier);
    }

    private void begin(YukkuriEntity carrier) {
        begun = true;
        startTick = carrier.level().getGameTime();
        if (endpoint != null) {
            ParentCarry.nestSpot(carrier, child, endpoint);
            GameplayLog.event(carrier, "family", "carry release target child={} placement={} at={}", child.getUUID(), placement == null ? "ground" : placement, endpoint);
        }
        if (approach != null) {
            approach.start(carrier);
            GameplayLog.event(carrier, "family", "carry release approaching child={} cushion={} yaw={}", child.getUUID(), ParentCarry.nestDestination(carrier, child), facing);
        }
        else if (destination == null) { turn = new Face(facing); turn.start(carrier); }
        else if (Math.abs(Mth.wrapDegrees(facing - carrier.bodyYaw())) > Face.FACE_ARRIVED) {
            turn = new Face(facing); turn.start(carrier);
        }
        else {
            previousLocal = destination.frame().toLocal(child.position());
            ParentCarry.lower(carrier, child, seat);
        }
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        if (released) {
            // The braid is free again: it takes her stick back (from her mouth, or from her own hat within reach).
            var back = stow.tick(carrier);
            return back instanceof Running running ? running : new Outcome.Done("released");
        }
        if (!begun) {
            var cleared = stow.tick(carrier);
            if (cleared instanceof Running running) return new Running(running.metric() + 4 * 360, TravelMode.CONSUMED);
            if (cleared instanceof Outcome outcome && outcome.reason() != null) return outcome;
            begin(carrier);
        }
        if (child.getVehicle() != carrier) return destination == null ? new Outcome.Done("already_free")
                : Outcome.of(Reason.DROPPED, 0, "already_free");
        if (destination != null && turn == null) {
            if (destination.entity().isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_removed");
            if (destination.capsized()) return new Outcome.Interrupted("host_capsized");
            var local = destination.frame().toLocal(child.position());
            lowered += local.distanceTo(previousLocal);
            previousLocal = local;
            String unavailable = seat.unavailable(child);
            if (unavailable != null) {
                var places = destination.places(child, Integer.MAX_VALUE);
                if (places.isEmpty()) return Outcome.of(Reason.NO_ROOM, 0, "no_seat check=" + unavailable);
                var replacement = nearest(carrier, places, List.of(carrier.bodyYaw()));
                if (replacement == null) return Outcome.of(Reason.NO_SUPPORT, 0, "transfer_blocked check=" + unavailable);
                GameplayLog.limited(carrier, "family", "lowering.target." + child.getUUID() + "." + unavailable, 200,
                        "carry lowering target moved child={} from={} to={} reason={}",
                        child.getUUID(), seat.local(), replacement.local(), unavailable);
                seat = replacement;
                settling = 0;
                ParentCarry.lower(carrier, child, seat);
                return new Running(-lowered, TravelMode.CONSUMED, true);
            }
        }
        if (approach != null) {
            var progress = approach.tick(carrier);
            if (progress instanceof Outcome outcome && outcome.reason() != null) return outsideAfter(carrier, outcome);
            if (!(progress instanceof Outcome.Done)) return progress instanceof Running running ? progress(running, 2) : progress;
            approach = null;
            if (!clearTurn(carrier, carrier.position(), facing)) return outsideAfter(carrier, Outcome.of(Reason.NO_ROOM, 0, "mouth_turn_blocked"));
            turn = new Face(facing);
            turn.start(carrier);
        }
        if (turn != null) {
            var turned = turn.tick(carrier);
            if (turned instanceof Running running) return progress(running, 1);
            if (destination == null) {
                var room = room(carrier, carrier.position(), carrier.bodyYaw(), ParentCarry.letGoPoint(carrier, child));
                if (!room.ok()) return outsideAfter(carrier, Outcome.of(room.reason(), room.shortfall(), room.detail()));
            } else {
                if (turned instanceof Outcome failed && failed.reason() != null) return failed;
                String unavailable = seat.unavailable(child);
                if (unavailable != null) return Outcome.of(Reason.NO_ROOM, 0, "place_lost_after_turn " + unavailable);
                previousLocal = destination.frame().toLocal(child.position());
            }
            turn = null;
            ParentCarry.lower(carrier, child, seat);
        }
        float down = ParentCarry.lowering(carrier, child, 0);
        var at = ParentCarry.letGoPoint(carrier, child);
        double remaining = CarryGeometry.releaseRemaining(new Vec(child.getX(), child.getY(), child.getZ()), new Vec(at.x, at.y, at.z),
                ParentCarry.pinned(carrier, child, at));
        if (down < 1 || remaining > .02) return destination != null ? new Running(-lowered, TravelMode.CONSUMED)
                : progress(new Running(Math.max(1 - down, remaining), TravelMode.CONSUMED), 0);
        // Reimu's baby is on the ground by the clip's closed marker; the lips still shut before the clip ends.
        int beat = ParentCarry.mouthReleaseLeft(carrier, child);
        if (beat > 0) return progress(new Running(beat / 100.0, TravelMode.CONSUMED), 0);
        if (destination != null) {
            if (!destination.steady()) {
                int left = ClipMarkers.of(BraidFetch.SETTLE).length() - settling++;
                return left > 0 ? new Running(-lowered - settling, TravelMode.CONSUMED)
                        : Outcome.of(Reason.NO_SUPPORT, 0, "host_unsteady");
            }
            if (!seat.land(child, "braid_set_down")) return Outcome.of(Reason.NO_CONTACT, 0, "seat_contact_lost");
            if (seat.kind() != SeatTarget.Kind.HEAD) ParentCarry.released(carrier, child, "release");
            GameplayLog.event(child, "body", "seat landed host={} place={} cause=braid_set_down", destination.entity().getUUID(), seat.kind());
        } else let(carrier, child, placement != null && !placement.equals("nest") ? placement : "release");
        released = true;
        var back = Stow.regrip(carrier, "release");
        if (stow.start(carrier, back)) return new Running(back.length(), TravelMode.CONSUMED, true);
        return new Outcome.Done("released");
    }

    private Running progress(Running step, int phase) {
        // Separate nearby walking, turning (at most 180 degrees) and lowering without compressing real progress.
        return new Running((("nest".equals(placement) ? 3 : 0) + phase) * 360 + step.metric(), step.travel(), step.replanned());
    }

    /** A child set down with the braid plays the braid clip's own set-down (place to release): the braid lowers it, opens and comes back. */
    @Override public HeldMove heldMove() { return stow.move(); }

    @Override public MotionPose pose() {
        if (!begun || released) return MotionPose.NONE;
        if (approach != null) return approach.pose();
        long since = carrier == null ? -1 : ParentCarry.setDownTick(carrier, child);
        if (since >= 0 && ParentCarry.mouthReleaseTicks(carrier, child, 0) >= 0) return new MotionPose(ParentCarry.MOUTH_RELEASE, since, false);
        if (since >= 0 && !ParentCarry.lowerClip(carrier, child).isEmpty()) {
            String clip = ParentCarry.lowerClip(carrier, child);
            int start = BraidFetch.marker(clip, "lift_start"), place = BraidFetch.marker(clip, "place");
            return carrier.level().getGameTime() - since < place - start ? new MotionPose(clip, since - start, false)
                    : new MotionPose(BraidFetch.SETTLE, since + place - start, false);
        }
        String clip = ParentCarry.braidLean(carrier, child) ? Grab.BRAID_LEAN : Grab.BRAID_LIFT;
        return since < 0 || ParentCarry.hold(carrier, child).reach() != Reach.BRAID ? MotionPose.NONE
                : new MotionPose(clip, since - ClipMarkers.of(clip).ticks("place", 38), false);
    }

    /** A child on its way down with the braid or the tail is not put back: the lowering runs to the ground. */
    @Override public boolean interruptible() { return carrier == null || ParentCarry.lowering(carrier, child, 0) < 0; }

    @Override public void abort(YukkuriEntity carrier, String why) {
        if (approach != null) approach.abort(carrier, why);
        if (child.getVehicle() == carrier) ParentCarry.stopLowering(carrier, child);
    }

    /** The same release at once, for an interruption, a death or an ended intent ({@code forced}: the room check is skipped and the body comes down where it is). Returns that check. */
    public static Feasibility now(YukkuriEntity carrier, YukkuriEntity child, boolean forced, String cause) {
        var room = forced ? Feasibility.pass() : new Release(child).room(carrier, carrier.position(), carrier.bodyYaw(), ParentCarry.letGoPoint(carrier, child));
        if (room.ok() && child.getVehicle() == carrier) let(carrier, child, cause);
        return room;
    }

    /**
     * The carry side of the interrupt bridge: an interrupted carrier lets every child go where it is, and an interrupted held child
     * (bar a fright) lets go of its carrier. A hit does not end a carry's own hold: a hit child stays held, and a hit carrier draws
     * once for one young ({@link ParentCarry#struck}); a young still being lifted, or held by another intent, is let go as before.
     */
    public static void interrupted(YukkuriEntity body, String why) {
        boolean hit = why.equals("hurt");
        for (var rider : body.getPassengers())
            if (rider instanceof YukkuriEntity child && !(hit && ParentCarry.keeps(body, child))) let(body, child, "carry_" + why);
        if (!why.equals("fright") && body.getVehicle() instanceof YukkuriEntity carrier && !(hit && ParentCarry.keeps(carrier, body)))
            let(carrier, body, "carry_child_" + why);
    }

    private static void let(YukkuriEntity carrier, YukkuriEntity child, String cause) {
        Motor.dismount(child, child.position(), Vec3.ZERO, cause);
        ParentCarry.released(carrier, child, cause);
    }

    @Override public void finished(YukkuriEntity carrier, Outcome result) {
        if (child.getVehicle() == carrier) ParentCarry.stopLowering(carrier, child);
        outcome = result;
        failedWorld = world(carrier);
        if (result.reason() != null) GameplayLog.limited(carrier, "family", "release." + child.getUUID() + "." + result.reason(), 200,
                "carry put down refused child={} outcome={} space={} stand={}",
                child.getUUID(), result, rejectedSpace, rejectedStand);
    }

    /** Failed attempts belong to this child. Elapsed time and unrelated motor outcomes cannot reopen them. */
    public boolean changed(YukkuriEntity carrier, BlockPos target) {
        return outcome == null || !Objects.equals(nest, target)
                || carrier.tickCount % 20 == 0 && failedWorld != world(carrier);
    }

    private int world(YukkuriEntity carrier) {
        if (destination != null && carrier.getVehicle() == destination.entity()) {
            // Hull translation and bobbing do not make another attempt useful; changed room or reach does.
            int state = Objects.hash(destination.capsized(), destination.bears(child), destination.frame().unit(), child.getBbWidth());
            for (var rider : destination.entity().getPassengers()) {
                var local = destination.riders().local(rider.getUUID());
                state += Objects.hash(rider.getId(), Math.round(local.x * 10), Math.round(local.y * 10), Math.round(local.z * 10),
                        Math.round(destination.riders().yaw(rider.getUUID()) / 5), rider.getBbWidth(), rider.getBbHeight());
            }
            return state;
        }
        var area = carrier.getBoundingBox().inflate(carrier.getBbWidth() * 3, DROP, carrier.getBbWidth() * 3);
        int state = Objects.hash(carrier.blockPosition(), Math.round(carrier.getX() * 10), Math.round(carrier.getZ() * 10),
                Math.round(carrier.bodyYaw() / 5), child.getBbWidth(), carrier.level().isRaining());
        for (var block : carrier.level().getBlockStates(area).toList()) state = 31 * state + block.hashCode();
        for (var other : carrier.level().getEntities(carrier, area,
                e -> e != carrier.getVehicle() && !carrier.hasPassenger(e) && (e instanceof YukkuriEntity || e instanceof SurfaceHost)))
            state += Objects.hash(other.getId(), Math.round(other.getX() * 10), Math.round(other.getY() * 10), Math.round(other.getZ() * 10),
                    Math.round(other.getYRot() / 5), other.getBbWidth(), other.getBbHeight());
        return state;
    }
}
