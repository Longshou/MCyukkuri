package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.family.ParentCarry.Hold;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.life.NestDoorway;
import io.github.longshou.mcyukkuri.life.NestResting;
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
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Put a held child down on supported ground (or, with a destination, onto a host's place). One candidate is an endpoint, the stand
 * she lowers it from and her facing there: she first turns where she stands so her hold's own let-go point lies over free ground,
 * and only walks to a stand when no bearing round her is free. Then she turns, lowers the child and it dismounts at its measured
 * feet. A blocked candidate (a walk that does not arrive, room lost at the real pose, a body at contact) is left out with its
 * footprint and the next one begun inside this same action, the child still held where it is. A braid that lowers a brim or head
 * child first moves her held stick out of the way ({@link Stow}); once the braid is free again it takes the stick back.
 */
public final class Release implements Action {
    public static final String ID = "release";
    /** How far below the let-go feet support or water has to exist (blocks). */
    private static final double DROP = 1.5;
    /** Candidates one set-down begins at most; each blocked one is left out and replaced, one failure counted once, here only. */
    private static final int TRIES = 6;
    /** Route plans one search may spend on stands she would walk to: one per bearing of the shared stand ring
     *  ({@link SurfaceApproach#contactStands}). */
    private static final int ROUTES = 16;
    /** Bearings of the turn ring round her stand, 15 degrees apart (the mouth's turn sweep is sampled every 5). */
    private static final int BEARINGS = 24;
    /** Progress phases: lowering, turning (at most 180 degrees), walking, moving the stick. One candidate spans them all, so the next
     *  candidate's metric starts below every value of the one before it and gets its own watchdog window. */
    private static final double PHASE = 360, SPAN = 5 * PHASE;

    private final YukkuriEntity child;
    private YukkuriEntity carrier;
    private float facing;
    private Face turn;
    private Walk approach;
    private final SurfaceHost destination;
    private SeatTarget seat;
    private boolean released;
    private BlockPos nest;
    private Vec3 endpoint, stand;
    private String placement;
    private Outcome outcome;
    /** This set-down's own search: footprints it failed at and stands it failed to reach or route to. They end with the action. */
    private final List<Vec3> failedEndpoints = new ArrayList<>(), failedStands = new ArrayList<>();
    /** Why the last search left candidates out, by refusal, for its exhaustion line. */
    private final Map<String, Integer> refused = new TreeMap<>();
    private int tries, failedWorld, settling;
    private double approachBest;
    private long approachSince;
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

    /** A set-down refused before it starts is a failed attempt like one that ends blocked: whoever holds the child next reads it. */
    @Override public Feasibility check(YukkuriEntity carrier) {
        var feasibility = feasibility(carrier);
        if (!feasibility.ok()) failed(carrier, Outcome.of(feasibility.reason(), feasibility.shortfall(), feasibility.detail()));
        return feasibility;
    }

    private Feasibility feasibility(YukkuriEntity carrier) {
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
        return plan(carrier);
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

    /**
     * The next candidate on the requested surface — this nest's pad, then supported ground outside it; or the ground round her. On
     * each she first turns where she stands (her hold's let-go point on the ring round her, or a pad point in reach; the smallest
     * turn first), then walks to a stand from which an endpoint is in reach (nearest endpoint first, a planned route). Footprints and
     * stands this set-down already failed at are left out.
     */
    private Feasibility plan(YukkuriEntity carrier) {
        refused.clear();
        var bed = BedContainer.of(child, nest);
        var exit = bed == null ? null : cushionExit(carrier);
        var here = carrier.position();
        // Her hold's own let-go distance; an endpoint up to the contact slack beyond it is still in reach.
        double natural = ParentCarry.groundPoint(carrier, child, 0).subtract(here).horizontalDistance(), reach = natural + .03;
        boolean turnHere = SurfaceApproach.supportedGround(carrier, here);
        int routes = 0;
        for (int pass = bed == null ? 1 : 0; pass < 2; pass++) {
            String where = pass == 0 ? "nest" : bed == null ? "ground" : "outside";
            var pad = pass == 0 ? pad(carrier, bed, exit) : null;
            var turnable = new ArrayList<Vec3>();
            if (pass == 0) {
                for (var at : pad) if (at.subtract(here).horizontalDistance() <= reach) turnable.add(at);
                turnable.sort(Comparator.comparingDouble(at -> Math.abs(Mth.wrapDegrees(facing(carrier, here, at) - carrier.bodyYaw()))));
            } else for (int i = 0; i < BEARINGS; i++) {
                float yaw = carrier.bodyYaw() + (i + 1) / 2 * (i % 2 == 0 ? 1 : -1) * 360f / BEARINGS;
                var at = floorUnder(ParentCarry.groundPoint(carrier, child, yaw));
                if (at != null && !turnable.contains(at) && !onPad(bed, at) && open(carrier, at, exit)) turnable.add(at);
            }
            if (turnHere) for (var at : turnable) if (admits(carrier, here, at)) return choose(carrier, here, at, where);
            // Endpoints she walks for lie at least half her width apart: a closer one is reached from the same stands.
            var walked = new ArrayList<Vec3>();
            for (var at : pass == 0 ? pad : ground(carrier, bed, exit)) {
                if (near(walked, at, carrier.getBbWidth() * .5)) continue;
                walked.add(at);
                for (var stand : SurfaceApproach.contactStands(carrier, at, natural, null, s -> !near(failedStands, s, carrier.getBbWidth() * .5))) {
                    if (stand.subtract(at).horizontalDistance() > reach) {
                        // Moved clear of a hat's brim beyond her reach of this endpoint.
                        refused.merge("reach", 1, Integer::sum);
                        continue;
                    }
                    if (!admits(carrier, stand, at)) continue;
                    if (routes++ == ROUTES) return exhausted(carrier, "release_routes_exhausted");
                    if (Double.isFinite(SurfaceApproach.routeLength(carrier, stand, null))) return choose(carrier, stand, at, where);
                    refused.merge("no_route", 1, Integer::sum);
                    failedStands.add(stand);
                }
            }
            if (pass == 0) GameplayLog.limited(carrier, "family", "release.nest_refused." + child.getUUID(), 200,
                    "carry release nest refused child={} nest={} from={} free={} refused={} next=outside",
                    child.getUUID(), nest, here, pad.size(), refused);
        }
        return exhausted(carrier, "no_supported_release_spot");
    }

    /** A resident bringing a child to a one-body cushion leaves room for its ordinary step across the low lip. */
    private AABB cushionExit(YukkuriEntity carrier) {
        var cushion = NestDoorway.bed(carrier, nest);
        if (cushion == null || cushion.bed() != NestResting.CUSHION
                || !cushion.owns(carrier.getUUID()) || cushion.owns(child.getUUID())) return null;
        var door = NestDoorway.shape(cushion);
        var centre = cushion.centre();
        double foot = Occupancy.foot(child.getBbWidth()), half = door.opening() + foot;
        double outer = door.outer() + carrier.getBbWidth() / 2 + foot;
        return new AABB(centre.add(-half, -1, -door.side() * foot), centre.add(half, 1, door.side() * outer));
    }

    /** Supported pad points of this nest for the child's own footprint, nearest her first. */
    private List<Vec3> pad(YukkuriEntity carrier, BedContainer bed, AABB exit) {
        double half = ((FloorShape.RoundedRect) bed.floor()).half(), step = child.getBbWidth() / 2;
        var centre = Vec3.ZERO.subtract(bed.toPlane(Vec3.ZERO));
        var out = new ArrayList<Vec3>();
        for (double x = -half; x <= half; x += step) for (double z = -half; z <= half; z += step) {
            var at = new Vec3(centre.x + x, bed.floorY(), centre.z + z);
            if (bed.standsInside(x, z, child.getBbWidth()) && open(carrier, at, exit)) out.add(at);
        }
        out.sort(Comparator.comparingDouble(carrier::distanceToSqr));
        return out;
    }

    /** Supported ground within two of her widths round her (off the requested nest's pad), nearest her first. */
    private List<Vec3> ground(YukkuriEntity carrier, BedContainer bed, AABB exit) {
        double half = carrier.getBbWidth() * 2, step = carrier.getBbWidth() / 2;
        var out = new ArrayList<Vec3>();
        for (double x = -half; x <= half; x += step) for (double z = -half; z <= half; z += step) {
            var at = floorUnder(carrier.position().add(x, 0, z));
            if (at != null && !out.contains(at) && !onPad(bed, at) && open(carrier, at, exit)) out.add(at);
        }
        out.sort(Comparator.comparingDouble(carrier::distanceToSqr));
        return out;
    }

    /** The highest resting floor for the child at or below {@code point}, down to {@link #DROP}. */
    private Vec3 floorUnder(Vec3 point) {
        for (double down = 0; down <= DROP; down += .5) {
            var at = SurfaceApproach.restingFloor(child, point.add(0, .1 - down, 0));
            if (at != null) return at;
        }
        return null;
    }

    private static boolean onPad(BedContainer bed, Vec3 at) {
        return bed != null && bed.floor().contains(bed.toPlane(at).x, bed.toPlane(at).z);
    }

    /** A footprint not failed at already, with support and no other body on it. */
    private boolean open(YukkuriEntity carrier, Vec3 at, AABB exit) {
        if (exit != null && exit.contains(at)) {
            refused.merge("cushion_exit", 1, Integer::sum);
            return false;
        }
        if (near(failedEndpoints, at, child.getBbWidth())) return false;
        var space = space(carrier, at);
        if (!space.ok()) refused.merge(space.detail().split(" ")[0], 1, Integer::sum);
        return space.ok();
    }

    private boolean admits(YukkuriEntity carrier, Vec3 stand, Vec3 at) {
        var room = room(carrier, stand, facing(carrier, stand, at), at);
        if (!room.ok()) refused.merge(room.detail().split(" ")[0], 1, Integer::sum);
        return room.ok();
    }

    private static boolean near(List<Vec3> failed, Vec3 at, double radius) {
        for (var point : failed) if (point.subtract(at).horizontalDistanceSqr() < radius * radius && Math.abs(point.y - at.y) < .5) return true;
        return false;
    }

    private Feasibility choose(YukkuriEntity carrier, Vec3 stand, Vec3 at, String where) {
        this.stand = stand;
        endpoint = at;
        facing = facing(carrier, stand, at);
        placement = where;
        approach = stand.equals(carrier.position()) ? null : new Walk(stand);
        return Feasibility.pass();
    }

    private Feasibility exhausted(YukkuriEntity carrier, String reason) {
        GameplayLog.limited(carrier, "family", "release.exhaustion." + child.getUUID(), 200,
                "carry release exhaustion child={} reason={} tries={} from={} yaw={} refused={} failedEndpoints={} failedStands={}",
                child.getUUID(), reason, tries, carrier.position(), carrier.bodyYaw(), refused, failedEndpoints.size(), failedStands.size());
        return Feasibility.of(Reason.NO_ROOM, 0, reason + " refused=" + refused);
    }

    /**
     * A blocked candidate: the lowering stops where the child is (never wound back), its footprint — and, when the walk or the turn
     * there failed, its stand — is left out, and the next candidate is begun. After {@link #TRIES} candidates the set-down ends
     * with the child still held.
     */
    private Progress retarget(YukkuriEntity carrier, Outcome refusal, boolean standFailed) {
        if (approach != null) approach.abort(carrier, "release_candidate_blocked");
        approach = null;
        turn = null;
        ParentCarry.stopLowering(carrier, child);
        failedEndpoints.add(endpoint);
        if (standFailed) failedStands.add(stand);
        var failed = endpoint;
        var next = tries < TRIES ? plan(carrier) : Feasibility.of(refusal.reason(), refusal.shortfall(), "release_tries_exhausted");
        GameplayLog.event(carrier, "family", "carry release candidate blocked child={} at={} standFailed={} refusal={} next={} tries={}",
                child.getUUID(), failed, standFailed, refusal, next.ok() ? endpoint : next.detail(), tries);
        if (!next.ok()) return Outcome.of(next.reason(), next.shortfall(), next.detail());
        begin(carrier);
        // The top of the new candidate's span: every metric it reports lies below, so its own progress opens a fresh window.
        return new Running((TRIES - tries + 1) * SPAN, TravelMode.CONSUMED, true);
    }

    /** Twice an action's ordinary window: the walk inside is judged at the ordinary one and replaced before this one runs out. */
    @Override public int progressWindow() { return 2 * Action.super.progressWindow(); }

    private float facing(YukkuriEntity carrier, Vec3 stand, Vec3 at) {
        var offset = ParentCarry.groundPoint(carrier, child, 0).subtract(carrier.position());
        return SurfaceApproach.yaw(stand, at) - SurfaceApproach.yaw(Vec3.ZERO, offset);
    }

    private Feasibility space(YukkuriEntity carrier, Vec3 at) {
        if (!SurfaceApproach.supportedGround(child, at)) return Feasibility.of(Reason.NO_SUPPORT, 0, "unsupported_or_wet at=" + at);
        if (!onPad(BedContainer.of(child, nest), at) && !SurfaceApproach.hasDryBankBuffer(child, at, DROP))
            return Feasibility.of(Reason.NO_SUPPORT, 0, "bank_edge at=" + at);
        var occupants = carrier.level().getEntitiesOfClass(YukkuriEntity.class, child.getDimensions(child.getPose()).makeBoundingBox(at).deflate(.005),
                other -> other != child && other != carrier && !carrier.hasPassenger(other));
        return occupants.isEmpty() ? Feasibility.pass()
                : Feasibility.of(Reason.NO_ROOM, 0, "occupied child=" + occupants.getFirst().getUUID() + " box=" + occupants.getFirst().getBoundingBox());
    }

    /** The one admission test of a candidate, planned and re-run at her real pose: the same geometry the lowering then closes on. */
    private Feasibility room(YukkuriEntity carrier, Vec3 stand, float yaw, Vec3 at) {
        // A stand she still walks to is reached within the walk's arrival tolerance: her body must clear the child from anywhere in it.
        double arrival = stand.equals(carrier.position()) ? 0 : SurfaceApproach.ARRIVAL;
        if (child.getDimensions(child.getPose()).makeBoundingBox(at)
                .intersects(carrier.getBoundingBox().move(stand.subtract(carrier.position())).inflate(arrival, 0, arrival)))
            return Feasibility.of(Reason.NO_ROOM, 0, "carrier_body");
        // The lowering ends where the feet meet the surface that holds them (ParentCarry.pinned: the host the carry began on or beside):
        // an endpoint that host still holds up is never reached, whatever the hold.
        var host = ParentCarry.carryHost(carrier, child);
        if (host != null && carrier.getVehicle() != host.entity() && ParentCarry.pinned(carrier, child, at) > at.y)
            return Feasibility.of(Reason.NO_ROOM, 0, "carry_host");
        var space = space(carrier, at);
        if (!space.ok()) return space;
        if (NestDoorway.rimAt(carrier, stand) != null) return Feasibility.of(Reason.NO_ROOM, 0, "stand_in_rim_band");
        if (!clearTurn(carrier, stand, yaw)) return Feasibility.of(Reason.NO_ROOM, 0, "mouth_turn_blocked");
        var hold = ParentCarry.hold(carrier, child);
        if (hold == Hold.BRIM && !ParentCarry.brimLevel(stand, at)) return Feasibility.of(Reason.NO_ROOM, 0, "brim_release_off_level");
        if (hold == Hold.BRIM && !ParentCarry.clearLowering(carrier, child, stand, yaw, at)
                || hold == Hold.HEAD && !ParentCarry.clearHeadLowering(carrier, child, stand, yaw, at))
            return Feasibility.of(Reason.NO_ROOM, 0, "lowering_route_blocked");
        return Feasibility.pass();
    }

    /** A mouth child keeps a place through the whole turn, seated as the passenger update seats it. */
    private boolean clearTurn(YukkuriEntity carrier, Vec3 stand, float yaw) {
        if (ParentCarry.hold(carrier, child) != Hold.MOUTH) return true;
        float span = Mth.wrapDegrees(yaw - carrier.bodyYaw());
        for (int i = 0, steps = Math.max(1, Mth.ceil(Math.abs(span) / 5)); i <= steps; i++)
            if (!ParentCarry.mouthSeatable(carrier, child, stand, carrier.bodyYaw() + span * i / steps)) return false;
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
        if (endpoint != null) {
            tries++;
            approachBest = Double.POSITIVE_INFINITY;
            approachSince = carrier.level().getGameTime();
            ParentCarry.nestSpot(carrier, child, endpoint);
            GameplayLog.event(carrier, "family", "carry release target child={} placement={} at={} stand={} try={}/{}",
                    child.getUUID(), placement, endpoint, stand, tries, TRIES);
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
            if (cleared instanceof Running running) return progress(running, 4);
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
            if (progress instanceof Outcome walked && walked.reason() != null) return retarget(carrier, walked, true);
            if (progress instanceof Running running) {
                // A walk that stops gaining (pressed against a body at its last step) is judged here at an action's ordinary window, and
                // this candidate is replaced; the set-down's own window is twice that (progressWindow), so the watchdog never ends it first.
                long now = carrier.level().getGameTime();
                if (running.metric() < approachBest - progressEpsilon() || running.replanned()) {
                    approachBest = Math.min(approachBest, running.metric());
                    approachSince = now;
                } else if (now - approachSince >= Action.super.progressWindow())
                    return retarget(carrier, Outcome.of(Reason.NO_PROGRESS, running.metric(),
                            String.format(Locale.ROOT, "approach_stalled left=%.2f", running.metric())), true);
                return progress(running, 2);
            }
            if (!(progress instanceof Outcome.Done)) return progress;
            approach = null;
            // She is there: a turn blocked from the yaw she arrived with leaves the stand usable for another endpoint.
            if (!clearTurn(carrier, carrier.position(), facing)) return retarget(carrier, Outcome.of(Reason.NO_ROOM, 0, "mouth_turn_blocked"), false);
            turn = new Face(facing);
            turn.start(carrier);
        }
        if (turn != null) {
            var turned = turn.tick(carrier);
            if (turned instanceof Running running) return progress(running, 1);
            if (destination == null) {
                if (turned instanceof Outcome failed && failed.reason() != null) return retarget(carrier, failed, true);
                var room = room(carrier, carrier.position(), carrier.bodyYaw(), ParentCarry.letGoPoint(carrier, child));
                if (!room.ok()) return retarget(carrier, Outcome.of(room.reason(), room.shortfall(), room.detail()), false);
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
        } else {
            // Contact is authoritative: a body that came onto the footprint during the lowering sends the child to the next candidate.
            var contact = space(carrier, child.position());
            if (!contact.ok()) return retarget(carrier, Outcome.of(contact.reason(), contact.shortfall(), "contact " + contact.detail()), false);
            let(carrier, child, "release");
        }
        released = true;
        var back = Stow.regrip(carrier, "release");
        if (stow.start(carrier, back)) return new Running(back.length(), TravelMode.CONSUMED, true);
        return new Outcome.Done("released");
    }

    private Running progress(Running step, int phase) {
        return new Running((TRIES - tries) * SPAN + phase * PHASE + step.metric(), step.travel(), step.replanned());
    }

    /** A child set down with the braid plays the braid clip's own set-down (place to release): the braid lowers it, opens and comes back. */
    @Override public HeldMove heldMove() { return stow.move(); }

    @Override public MotionPose pose() {
        if (!begun || released) return MotionPose.NONE;
        if (approach != null) return approach.pose();
        long since = carrier == null ? -1 : ParentCarry.setDownTick(carrier, child);
        if (since >= 0 && ParentCarry.mouthReleaseTicks(carrier, child, 0) >= 0) return new MotionPose(ParentCarry.MOUTH_RELEASE, since, false);
        // A brim young walks to the brim edge first; the lowering clip starts so its tuft is there as it steps on.
        long brim = ParentCarry.brimLowerStart(carrier, child);
        if (brim >= 0) return new MotionPose(ParentCarry.BRIM_LOWER, brim, false);
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
     * (bar a fright) lets go of its carrier. Fright keeps the family holds for flight. A hit child stays held, and a hit carrier
     * draws once for one young ({@link ParentCarry#struck}); a young still being lifted can lose its grip on a real hit.
     */
    public static void interrupted(YukkuriEntity body, String why) {
        boolean hit = why.equals("hurt");
        boolean fright = why.equals("fright");
        for (var rider : body.getPassengers())
            if (rider instanceof YukkuriEntity child && !((hit || fright) && ParentCarry.keeps(body, child))
                    && !(fright && body.family().carry().ownsLift(child))) let(body, child, "carry_" + why);
        if (!fright && body.getVehicle() instanceof YukkuriEntity carrier && !(hit && ParentCarry.keeps(carrier, body)))
            let(carrier, body, "carry_child_" + why);
    }

    private static void let(YukkuriEntity carrier, YukkuriEntity child, String cause) {
        Motor.dismount(child, child.position(), Vec3.ZERO, cause);
        ParentCarry.released(carrier, child, cause);
    }

    @Override public void finished(YukkuriEntity carrier, Outcome result) {
        if (child.getVehicle() == carrier) ParentCarry.stopLowering(carrier, child);
        failed(carrier, result);
    }

    /** The attempt's end, refused at its check or ended running; a real failure is told to the carrier's carry, so whoever holds
     *  the child after an ended intent inherits it ({@link ParentCarry#finish}). */
    private void failed(YukkuriEntity carrier, Outcome result) {
        outcome = result;
        failedWorld = world(carrier);
        if (result.reason() == null) return;
        carrier.family().carry().releaseFailed(this);
        GameplayLog.limited(carrier, "family", "release." + child.getUUID() + "." + result.reason(), 200,
                "carry put down refused child={} outcome={} tries={}", child.getUUID(), result, tries);
    }

    /** Whether a set-down of this child onto {@code host} (null: the ground) at {@code target} may be tried anew. Failed attempts
     *  belong to this child and this request; elapsed time and unrelated motor outcomes cannot reopen them, only a changed world. */
    public boolean changed(YukkuriEntity carrier, SurfaceHost host, BlockPos target) {
        return outcome == null || destination != host || !Objects.equals(nest, target)
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
