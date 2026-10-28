package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.BraidCarry;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriLocomotion;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/**
 * The single way a body gets between ground, water and a seat of a hat boat, and between two seats: one ticket, one set of phases.
 *
 * <pre>
 *  CLAIM    the destination seat is taken from the {@link HatDeck}; the plan is checked once (strength, reach, block sweep)
 *  APPROACH the walker (or carrier) gets to its take-off place / pickup seat and faces the work
 *  CONTACT  bite, hook or cradle closes; the length is the clip's own marker
 *  MOVE     the body follows a curve given in the frame of the boat it belongs to; while in a boat's frame it is that boat's
 *           passenger and {@code positionRider} evaluates the curve, so rocking, drifting and heeling carry it. Crossing the rim
 *           switches the frame once. One MOVE per boat at a time; the hull is held on a helm lease while it lasts.
 *  SETTLE   the body is on its seat (or standing); the clip's tail
 *  RELEASE  contact let go, the carrier goes back to her place
 * </pre>
 *
 * Modes: HOP (own jump), CLIMB (mouth on the rim, braid hooked over it, pull and slide), LIFT (a carrier's braid cradle; a mouth
 * bite for a braidless carrier or when the braid cannot reach, one degrade step). Every refusal and failure is one of the four
 * classes of {@link Refusal}; every phase has a time limit and a progress watchdog ends a stalled ticket. A failed MOVE inside a
 * boat's frame runs the curve backwards to its start; outside any boat the body is simply let go and falls.
 *
 * <p>The ticket is driven from {@code CrewTransferGoal}; the only other writer of a body's position is the boat's
 * {@code positionRider}.
 */
public final class CrewTransfer {
    public enum Mode { HOP, CLIMB, LIFT }
    public enum Phase { CLAIM, APPROACH, CONTACT, MOVE, SETTLE, RELEASE }
    /** GEOMETRY: deterministic, never retried as is. TRANSIENT: wait a limited time. SLIP: counted per event. INCAPABLE: a real consequence. */
    public enum Refusal { GEOMETRY, TRANSIENT, SLIP, INCAPABLE }
    public enum Contact { NONE, BRAID_CRADLE, BRAID_HOOK, MOUTH_BITE, PUSH }
    public enum Kind { GROUND, WATER, BERTH }

    /** A place a body is, or goes to: world ground, world water, or a seat of a hull. */
    public record Anchor(Kind kind, Vec3 pos, int boat, int slot) {
        public static Anchor ground(Vec3 at) { return new Anchor(Kind.GROUND, at, -1, DeckLayout.NONE); }
        public static Anchor water(Vec3 at) { return new Anchor(Kind.WATER, at, -1, DeckLayout.NONE); }
        public static Anchor berth(HatBoatEntity hat, int slot) { return new Anchor(Kind.BERTH, null, hat.getId(), slot); }
        public String key() {
            return kind == Kind.BERTH ? "berth:" + boat + ":" + slot
                    : kind.name().toLowerCase(Locale.ROOT) + ":" + Math.round(pos.x * 4) + "," + Math.round(pos.y * 4) + "," + Math.round(pos.z * 4);
        }
    }

    /** What a caller asks for. {@code carrierSlot} is the seat the carrier ends on after a lift inside a hull (a care station), or NONE. */
    public record Request(Mode mode, YukkuriEntity body, YukkuriEntity carrier, Anchor from, Anchor to, Purpose purpose, boolean emergency,
            boolean rescue, int carrierSlot, String why) {}

    public record Result(CrewTransfer ticket, Refusal refusal, String reason) {
        public boolean ok() { return ticket != null; }
        static Result refused(Refusal refusal, String reason) { return new Result(null, refusal, reason); }
        public static Result refusedClient() { return new Result(null, Refusal.TRANSIENT, "client"); }
    }

    /** A read-only answer to whether the current water-to-hat lift can be planned; opening the real ticket rechecks and claims its seat. */
    public record LiftPreview(boolean ok, Refusal refusal, String reason) {
        private static LiftPreview ready() { return new LiftPreview(true, null, ""); }
        private static LiftPreview refused(Refusal refusal, String reason) { return new LiftPreview(false, refusal, reason); }
    }

    /** Phase time limits in ticks (APPROACH differs by mode; MOVE adds its own length). */
    private static final int CLAIM_TTL = 40, CONTACT_TTL = 260, SETTLE_TTL = 80, RELEASE_TTL = 60, STALL_TICKS = 200, MOVE_SLACK = 60;
    private static final AtomicLong IDS = new AtomicLong(1);

    // ---------------------------------------------------------------- identity
    private final long id;
    private final ServerLevel level;
    private final Mode mode;
    private final UUID bodyId;
    private final UUID carrierId;
    private final Anchor from, to;
    private final Purpose purpose;
    private final boolean emergency, rescue;
    private final int carrierSlot;
    private final String why;
    private final long created;
    private final int boatA, boatB;

    // ---------------------------------------------------------------- state
    private Phase phase = Phase.CLAIM;
    private long phaseStart, lastTick = Long.MIN_VALUE, moveStart = -1, revertAt = -1, contactStart;
    private double revertFrom;
    private boolean finished, holdsMove, bodyHeld, reverting, degraded, mounted, claimed, arrived, rimBoarded;
    private long claimedLease = -1, carrierLease = -1;
    private Refusal pendingRefusal;
    private String pendingReason = "";
    private Contact contact = Contact.NONE;
    private int contactFrame;
    private Vec3 contactAt, contactPrev;
    private Vec3 bodyAt, bodyPrev;
    private float bodyYaw, prevBodyYaw;
    private Geometry geometry;
    private Stand stand;
    private int slot = DeckLayout.NONE, approachTries, ticksTotal, standStarts;
    private double loadBefore, loadAfter, bestProgress = Double.MAX_VALUE;
    private double movedDistance;
    private int departureSlot = DeckLayout.NONE, blockedMoveTicks;
    private String geometryKey;
    private long bestProgressAt;
    private Vec3 plannedStart, takeoffStand, previewStart;
    private final List<Vec3> refusedStands = new ArrayList<>();
    private long blockedStandSince;
    private DeckSurface deckSurface;
    private long deckSurfaceTick = Long.MIN_VALUE;
    private HatBoatEntity deckSurfaceHull;
    private Vec3 deckSurfaceOrigin;
    private float deckSurfaceYaw;
    private int deckSurfaceCargo;
    private List<AABB> deckObstacles = List.of();
    private CrewState.Entry entry;

    /** The finite geometry of a trip: the curve, how long it takes, which rim it crosses. */
    private record Geometry(boolean arc, List<CrewState.Knot> knots, List<Double> cumulative, int moveTicks, String curve, String entryId,
            Vec3 start, Vec3 end, double length) {}
    /** Where a carrier stands (a seat for a boat carrier, her feet otherwise) and the heading from which her reach closes. */
    private record Stand(int seat, Vec3 feet, float yaw, boolean mouth) {}

    private CrewTransfer(ServerLevel level, Request r, int boatA, int boatB) { this(level, r, boatA, boatB, true); }

    /** A planning-only transfer has no observable id and must never be attached, published or driven. */
    private CrewTransfer(ServerLevel level, Request r, int boatA, int boatB, boolean allocateId) {
        this.id = allocateId ? IDS.getAndIncrement() : 0;
        this.level = level;
        this.mode = r.mode();
        this.bodyId = r.body().getUUID();
        this.carrierId = r.carrier() == null ? null : r.carrier().getUUID();
        this.from = r.from();
        this.to = r.to();
        this.purpose = r.purpose();
        this.emergency = r.emergency();
        this.rescue = r.rescue();
        this.carrierSlot = r.carrierSlot();
        this.why = r.why();
        this.created = level.getGameTime();
        this.boatA = boatA;
        this.boatB = boatB;
    }

    // ---------------------------------------------------------------- accessors
    public long id() { return id; }
    public Mode mode() { return mode; }
    public Phase phase() { return phase; }
    public UUID bodyId() { return bodyId; }
    public UUID carrierId() { return carrierId; }
    public boolean finished() { return finished; }
    public boolean holdsMove() { return holdsMove; }
    public Contact contact() { return contact; }
    public int slot() { return slot; }
    public CrewState.Entry entry() { return entry; }
    /** The body is in the carrier's or rim's hold: from the contact until the release. */
    public boolean holdsBody() { return bodyHeld; }
    public long phaseStart() { return phaseStart; }
    /** When the contact closed on (the start of the clip). */
    public long contactStart() { return contactStart; }
    /** The hull whose CrewState carries the ticket for the clients. */
    public int anchorBoat() { return boatB >= 0 ? boatB : boatA; }
    public boolean touches(HatBoatEntity hat) { return hat != null && (hat.getId() == boatA || hat.getId() == boatB); }
    public boolean withdrawing() { return reverting; }
    public Anchor from() { return from; }
    public Anchor to() { return to; }
    public boolean rescue() { return rescue; }
    /** Observe the executed jump segment; the following supported deck walk is not airborne. */
    public boolean airborne() {
        if (phase != Phase.MOVE || mode == Mode.LIFT) return false;
        return mode != Mode.HOP || entry == null || entry.airborne(level.getGameTime());
    }

    /**
     * The one exception to a hull's transfer exclusion: this ticket's own HOP body may walk to its departure edge while it is still
     * in APPROACH. Every other participant, phase and mode remains excluded.
     */
    public boolean allowsDepartureApproach(HatBoatEntity source, YukkuriEntity rider) {
        return !finished && source != null && rider != null && phase == Phase.APPROACH && mode == Mode.HOP
                && bodyId.equals(rider.getUUID()) && from.kind() == Kind.BERTH && boatA == source.getId() && rider.getVehicle() == source;
    }

    private YukkuriEntity body() { return level.getEntity(bodyId) instanceof YukkuriEntity y ? y : null; }
    private YukkuriEntity carrier() { return carrierId != null && level.getEntity(carrierId) instanceof YukkuriEntity y ? y : null; }
    private HatBoatEntity hatA() { return boatA >= 0 && level.getEntity(boatA) instanceof HatBoatEntity h ? h : null; }
    private HatBoatEntity hatB() { return boatB >= 0 && level.getEntity(boatB) instanceof HatBoatEntity h ? h : null; }
    /** The hull the carrier rides, if any. */
    private HatBoatEntity carrierHat(YukkuriEntity carrier) { return carrier != null && carrier.getVehicle() instanceof HatBoatEntity h ? h : null; }
    private YukkuriEntity driver() {
        var c = carrier();
        return c != null && mode == Mode.LIFT ? c : body();
    }

    // ================================================================= opening a ticket
    /**
     * Check the request, take the destination seat and open the ticket in APPROACH, or refuse with exactly one class and reason.
     * The refusal is logged once per (body, reason) with the anchors; an accepted ticket logs once when it ends.
     */
    public static Result open(ServerLevel level, Request r) {
        var body = r.body();
        var carrier = r.carrier();
        long now = level.getGameTime();
        var port = body.family().crew();
        if (r.from().kind() == Kind.BERTH && r.to().kind() == Kind.BERTH && r.from().boat() == r.to().boat()
                && r.to().slot() >= 0 && r.from().slot() == r.to().slot())
            return refuse(r, Refusal.GEOMETRY, "same_anchor", false);
        if (port.active() || carrier != null && carrier.family().crew().active()) return refuse(r, Refusal.TRANSIENT, "busy", false);
        if (!usable(body) || carrier != null && !carrierReady(carrier)) return refuse(r, Refusal.TRANSIENT, "participant_not_ready", false);
        if (r.mode() != Mode.LIFT && !selfCapable(body, r.mode())) return refuse(r, Refusal.INCAPABLE, "body_or_energy", false);
        if (r.mode() == Mode.LIFT) {
            if (carrier == null) return refuse(r, Refusal.INCAPABLE, "no_carrier", false);
            if (!CrewReach.canLift(carrier, body)) return refuse(r, Refusal.INCAPABLE, "lift_capacity", false);
        }
        if (r.mode() == Mode.CLIMB && !body.isInWaterOrBubble()) return refuse(r, Refusal.GEOMETRY, "climb_needs_water", false);
        if (r.mode() == Mode.HOP && body.isInWaterOrBubble() && r.from().kind() != Kind.BERTH) return refuse(r, Refusal.GEOMETRY, "hop_from_water", false);
        HatBoatEntity a = r.from().kind() == Kind.BERTH ? hat(level, r.from().boat()) : null;
        HatBoatEntity b = r.to().kind() == Kind.BERTH ? hat(level, r.to().boat()) : null;
        // "The same pair of anchors" includes where the hulls and the carrier stand: moving the boat or the carrier is a new pair.
        String key = r.mode() + ">" + r.from().key() + ">" + r.to().key() + "|" + where(carrier) + where(a) + where(b);
        if (port.knownImpossible(key, now)) return refuse(r, Refusal.GEOMETRY, "known_impossible", false);
        if (r.from().kind() == Kind.BERTH && a == null || r.to().kind() == Kind.BERTH && b == null) return refuse(r, Refusal.GEOMETRY, "hull_missing", false);
        // A cradle has no deck seat, so a braid lift to or from it is not a route any more (spec 14 WP1 删除; WP5 deletes the mode).
        if (r.mode() == Mode.LIFT && (a != null && !a.usesDeck() || b != null && !b.usesDeck()))
            return refuse(r, Refusal.GEOMETRY, "nest_no_deck", false);
        // A hull is not needed to pull a swimmer up onto the bank: the carrier's braid or mouth is the whole of that trip.
        boolean bankPull = r.mode() == Mode.LIFT && r.from().kind() != Kind.BERTH && r.to().kind() == Kind.GROUND;
        if (a == null && b == null && !bankPull) return refuse(r, Refusal.GEOMETRY, "no_hull", false);
        for (var hat : new HatBoatEntity[]{a, b}) {
            if (hat == null) continue;
            if (hat.isRemoved() || !(hat.isAfloat() || hat.catches() || hat.state() == HatBoatEntity.State.BEACHED) || hat.unsteadyMotion()
                    || hat.flooding() > 0 || hat.capsizing()) return refuse(r, Refusal.TRANSIENT, "hull_unstable", false);
        }
        if (r.mode() == Mode.CLIMB && b != null) {
            if (!climbInReach(body, b)) return refuse(r, Refusal.TRANSIENT, "rim_out_of_reach", false);
        }
        var ticket = new CrewTransfer(level, r, a == null ? -1 : a.getId(), b == null ? -1 : b.getId());
        ticket.geometryKey = key;
        // External boarding may have to feed, jettison or move another rider. Check the exact eventual seat and curve first.
        var preflight = ticket.preflightExternalBoarding();
        if (preflight != null) {
            if (preflight.refusal() == Refusal.GEOMETRY) port.rememberImpossible(key, now);
            return refuse(r, preflight.refusal(), preflight.reason(), false);
        }
        var claim = ticket.claim();
        if (claim != null) return refuse(r, claim.refusal(), claim.reason(), false);
        // The plan is checked now (strength, reach, block sweep); the curve is built again from the real positions at MOVE.
        var planned = ticket.plan();
        if (planned != null) {
            ticket.releaseClaim(body, false);
            if (planned.refusal() == Refusal.GEOMETRY) port.rememberImpossible(key, now);
            return refuse(r, planned.refusal(), planned.reason(), false);
        }
        ticket.start();
        if (ticket.finished()) return Result.refused(Refusal.INCAPABLE, "energy_unavailable");
        return new Result(ticket, null, "");
    }

    private static String where(net.minecraft.world.entity.Entity e) {
        return e == null ? "-" : Math.round(e.getX() * 4) + "," + Math.round(e.getY() * 4) + "," + Math.round(e.getZ() * 4) + "/" + Math.round(e.getYRot() / 15f) + ";";
    }

    private static Result refuse(Request r, Refusal refusal, String reason, boolean quiet) {
        if (!quiet) GameplayLog.limited(r.body(), "aquatic", "transfer.refuse." + reason, 100,
                "transfer refused mode={} why={} reason={} class={} from={} to={} body={} carrier={}", r.mode(), r.why(), reason, refusal,
                r.from().key(), r.to().key(), r.body().getUUID(), r.carrier() == null ? null : r.carrier().getUUID());
        return Result.refused(refusal, reason);
    }

    private static HatBoatEntity hat(ServerLevel level, int id) { return level.getEntity(id) instanceof HatBoatEntity h ? h : null; }

    private static boolean usable(YukkuriEntity y) {
        return y.isAlive() && !y.isNoAi() && !y.isCommandPlaying() && !y.operations().active() && !y.isOnFire() && y.level() instanceof ServerLevel;
    }
    private static boolean carrierReady(YukkuriEntity c) {
        return usable(c) && !c.life().isSleeping() && c.life().mealTicks() == 0 && !c.family().tiedToLabor();
    }
    /** Jumping, climbing and pulling oneself up need a body that can and energy to spend. */
    public static boolean canStepOut(YukkuriEntity y) { return selfCapable(y, Mode.HOP); }
    /** Read-only joint choice of ground and a reachable departure seat, before the voyage commits to a landing point. */
    public static boolean canHopToGround(YukkuriEntity body, HatBoatEntity hull, Vec3 target) {
        if (!(body.level() instanceof ServerLevel level) || !selfCapable(body, Mode.HOP) || body.getVehicle() != hull) return false;
        var placed = hull.deck().get(body.getUUID());
        if (placed == null) return false;
        var request = new Request(Mode.HOP, body, null, Anchor.berth(hull, placed.slot()), Anchor.ground(target),
                Purpose.BOARD, false, false, DeckLayout.NONE, "land_hop");
        return new CrewTransfer(level, request, hull.getId(), -1, false).plan() == null;
    }
    private static boolean selfCapable(YukkuriEntity y, Mode mode) {
        if (y.family().adult() || y.childhood().canJump()) return y.life().energy() >= 8 && !y.life().isSleeping() && y.family().stage() != FamilyLife.Stage.BABY;
        return false;
    }

    // ================================================================= planning
    /** Builds and checks the plan; returns the failure, or null when the trip can be made. */
    private Result plan() {
        var body = body();
        var carrier = carrier();
        if (body == null) return Result.refused(Refusal.TRANSIENT, "body_missing");
        var built = geometry(body);
        if (built.failure() != null) return built.failure();
        geometry = built.geometry();
        if (mode == Mode.LIFT) {
            var chosen = chooseStand(body, carrier, geometry);
            if (chosen == null) return Result.refused(Refusal.GEOMETRY, "no_reach");
            stand = chosen;
            degraded = chosen.mouth();
        }
        return null;
    }

    private record Built(Geometry geometry, Result failure) {}

    private Built geometry(YukkuriEntity body) {
        return geometry(body, previewStart == null ? body.position() : previewStart);
    }

    /** The same lift plan, evaluated before a rider walks to a pickup seat; no claim, energy or ticket is changed. */
    public static boolean canLandFromSeat(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity hat, int seat, Vec3 ground) {
        if (!(body.level() instanceof ServerLevel level) || !usable(body) || !carrierReady(carrier) || !CrewReach.canLift(carrier, body)) return false;
        var request = new Request(Mode.LIFT, body, carrier, Anchor.berth(hat, seat), Anchor.ground(ground), Purpose.BOARD,
                false, false, DeckLayout.NONE, "land_lift");
        var preview = new CrewTransfer(level, request, hat.getId(), -1, false);
        var built = preview.geometry(body, hat.toWorld(hat.deckLayout().slot(seat).pos()));
        return built.failure() == null && preview.chooseStand(body, carrier, built.geometry()) != null;
    }

    /**
     * Plans a possible rescue lift without reserving a seat, moving a rider, changing an incident or spending resources.
     * Deck searches retain their bounded-search diagnostics. The actual transfer rechecks the selected seat after any approach.
     */
    public static LiftPreview previewLiftAboard(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity hat) {
        if (body == null || carrier == null || hat == null || !(body.level() instanceof ServerLevel level)
                || carrier.level() != body.level() || hat.level() != body.level()) return LiftPreview.refused(Refusal.TRANSIENT, "participants_unavailable");
        if (!usable(body) || !carrierReady(carrier) || carrier.getVehicle() != hat || hat.isRemoved() || !hat.isAfloat() || hat.unsteadyMotion())
            return LiftPreview.refused(Refusal.TRANSIENT, "participants_not_ready");
        if (body.isPassenger() || !body.isInWaterOrBubble() || !body.waterDistress().canBeLifted())
            return LiftPreview.refused(Refusal.TRANSIENT, "body_not_liftable");
        if (body.distanceToSqr(hat) > 9) return LiftPreview.refused(Refusal.GEOMETRY, "approach_required");
        if (!carrier.hasLineOfSight(body)) return LiftPreview.refused(Refusal.TRANSIENT, "line_of_sight_required");
        if (!CrewReach.canLift(carrier, body)) return LiftPreview.refused(Refusal.INCAPABLE, "lift_capacity");
        if (!hat.admits(body)) return LiftPreview.refused(Refusal.INCAPABLE, "not_admitted");
        var request = new Request(Mode.LIFT, body, carrier, Anchor.water(body.position()), Anchor.berth(hat, DeckLayout.NONE), Purpose.RESCUE,
                true, true, DeckLayout.NONE, "rescue_lift_preview");
        var preview = new CrewTransfer(level, request, -1, hat.getId(), false);
        var result = preview.preflightExternalBoarding();
        return result == null ? LiftPreview.ready() : LiftPreview.refused(result.refusal(), result.reason());
    }

    /** The common carry preflight uses the same curve and stand solver without reserving, spending or moving. */
    public static LiftPreview previewLift(Request request) {
        if (!(request.body().level() instanceof ServerLevel level) || request.mode() != Mode.LIFT)
            return LiftPreview.refused(Refusal.TRANSIENT, "participants_unavailable");
        if (!usable(request.body()) || request.carrier() != null && !carrierReady(request.carrier()))
            return LiftPreview.refused(Refusal.TRANSIENT, "participant_not_ready");
        if (request.body().family().crew().active() || request.carrier() != null && request.carrier().family().crew().active())
            return LiftPreview.refused(Refusal.TRANSIENT, "busy");
        if (request.carrier() == null || !CrewReach.canLift(request.carrier(), request.body()))
            return LiftPreview.refused(Refusal.INCAPABLE, "lift_capacity");
        if (request.from().kind() == Kind.BERTH && request.to().kind() == Kind.BERTH
                && request.from().boat() == request.to().boat() && request.from().slot() == request.to().slot()
                && request.to().slot() >= 0) return LiftPreview.refused(Refusal.GEOMETRY, "same_anchor");
        for (var anchor : new Anchor[]{request.from(), request.to()}) if (anchor.kind() == Kind.BERTH) {
            var hull = hat(level, anchor.boat());
            if (hull == null) return LiftPreview.refused(Refusal.TRANSIENT, "hull_missing");
            if (hull.isRemoved() || !(hull.isAfloat() || hull.catches() || hull.state() == HatBoatEntity.State.BEACHED)
                    || hull.unsteadyMotion() || hull.flooding() > 0 || hull.capsizing())
                return LiftPreview.refused(Refusal.TRANSIENT, "hull_unstable");
        }
        var preview = new CrewTransfer(level, request, request.from().boat(), request.to().boat(), false);
        preview.slot = request.to().slot();
        var result = request.to().kind() == Kind.BERTH && request.from().boat() != request.to().boat()
                ? preview.preflightExternalBoarding() : preview.plan();
        return result == null ? LiftPreview.ready() : LiftPreview.refused(result.refusal(), result.reason());
    }

    /** A transported child's eventual dry feet, evaluated with no relocation, claim or resource change. */
    public static LiftPreview previewBankLoadingAt(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity hat, Vec3 feet) {
        if (!(body.level() instanceof ServerLevel level) || !usable(body) || !carrierReady(carrier) || !CrewReach.canLift(carrier, body))
            return LiftPreview.refused(Refusal.INCAPABLE, "participants_not_ready");
        var request = new Request(Mode.LIFT, body, carrier, Anchor.ground(feet), Anchor.berth(hat, DeckLayout.NONE),
                Purpose.BOARD, false, false, DeckLayout.NONE, "load_lift_preview");
        var preview = new CrewTransfer(level, request, -1, hat.getId(), false);
        preview.previewStart = feet;
        var result = preview.preflightExternalBoarding();
        return result == null ? LiftPreview.ready() : LiftPreview.refused(result.refusal(), result.reason());
    }

    private static Refusal admissionRefusal(Admission admission) {
        return switch (admission.reason()) {
            case UNITS_FULL, NOT_ADMITTED -> Refusal.INCAPABLE;
            case NO_SLOT_FOR_CLASS -> Refusal.GEOMETRY;
            default -> Refusal.TRANSIENT;
        };
    }

    private record BoardingSlot(int slot, Result failure) {
        private static BoardingSlot at(int slot) { return new BoardingSlot(slot, null); }
        private static BoardingSlot refused(Refusal refusal, String reason) { return new BoardingSlot(DeckLayout.NONE, Result.refused(refusal, reason)); }
    }

    /**
     * The external-boarder preflight shares the real ticket's geometry and stand selection, but chooses the same eventual seat
     * without taking it. When room-making must lower the larder tier, the exact candidate is projected before food is changed;
     * when it must move riders, the room plan's own destination is used. {@link #claim()} then requires that exact seat.
     */
    private Result preflightExternalBoarding() {
        if (to.kind() != Kind.BERTH || a_withinBoat()) return null;
        var body = body();
        var b = hatB();
        if (body == null || b == null) return Result.refused(Refusal.TRANSIENT, "participants_unavailable");
        // Every external destination helper requires a floating hull. BOARD/RESCUE also refuse while a hull is being landed;
        // establish that before makeRoom can consume provisions or start another rider's move.
        if (!b.isAfloat()) return Result.refused(Refusal.TRANSIENT, "destination_not_afloat");
        if ((purpose == Purpose.BOARD || purpose == Purpose.RESCUE) && b.landing())
            return Result.refused(Refusal.TRANSIENT, "destination_landing");
        var placed = b.deck().get(bodyId);
        boolean retainedReturn = purpose == Purpose.RETAINED && placed != null && !placed.aboard() && placed.lease() != null
                && placed.lease().purpose() == Purpose.RETAINED && placed.home() >= 0;
        // Boarders contesting a rescue berth must already be close enough to do so. A valid retained return does not contest
        // one at all; its existing berth is reclaimed below.
        if (emergency && b.young(body) && (purpose == Purpose.BOARD || purpose == Purpose.RETAINED && !retainedReturn)
                && !b.canRequestEmergencyBerth(body)) return Result.refused(Refusal.TRANSIENT, "emergency_berth_unreachable");
        var selected = externalBoardingSlot(body, b);
        if (selected.failure() != null) return selected.failure();
        slot = selected.slot();
        var first = plan();
        if (first == null || first.refusal() != Refusal.GEOMETRY || to.slot() >= 0
                || retainedReturn || placed != null && placed.target() >= 0) return first;
        // Capacity alone cannot choose the far end of the hat for a short braid. Try other actual unclaimed seats
        // before anyone spends food or reserves the unreachable first choice.
        var purposes = purpose == Purpose.OWN ? List.of(Purpose.OWN) : purpose == Purpose.RESCUE ? List.of(Purpose.RESCUE)
                : emergency ? List.of(Purpose.BOARD, Purpose.RESCUE) : List.of(Purpose.BOARD);
        int tested = 0;
        for (var candidatePurpose : purposes) for (int candidate : b.deck().freeSeats(b.bodyOf(body), candidatePurpose, b.toLocal(body.position()))) {
            if (candidate == selected.slot()) continue;
            if (++tested > 24) break;
            slot = candidate;
            var result = plan();
            if (result == null) return null;
        }
        slot = selected.slot();
        return first;
    }

    /** Pick the target consumed by the real destination claim, without placing or moving anybody. */
    private BoardingSlot externalBoardingSlot(YukkuriEntity body, HatBoatEntity b) {
        var deck = b.deck();
        var placed = deck.get(bodyId);
        var bodyView = b.bodyOf(body);
        var entry = nearestEntry(b, body.position());
        var near = entry == null ? b.toLocal(body.position()) : entry.edge();

        if (purpose == Purpose.OWN) {
            var own = deck.canAdmit(bodyView, Purpose.OWN, near, DeckLayout.NONE);
            return own.ok() ? BoardingSlot.at(own.slot()) : BoardingSlot.refused(admissionRefusal(own), "seat:" + own.text());
        }
        if (purpose == Purpose.RETAINED && placed != null && !placed.aboard() && placed.lease() != null
                && placed.lease().purpose() == Purpose.RETAINED && placed.home() >= 0) return BoardingSlot.at(placed.home());
        // Boarding and rescue reservations are renewed by the destination helper, rather than creating a second claim.
        if (placed != null && !placed.aboard() && placed.target() >= 0) return BoardingSlot.at(placed.target());

        Purpose primary = purpose == Purpose.RESCUE ? Purpose.RESCUE : Purpose.BOARD;
        var direct = deck.canAdmit(bodyView, primary, near, DeckLayout.NONE);
        if (direct.ok()) return BoardingSlot.at(direct.slot());
        if (primary == Purpose.BOARD && (emergency || rescue)) {
            var rescueSeat = deck.canAdmit(bodyView, Purpose.RESCUE, near, DeckLayout.NONE);
            if (rescueSeat.ok()) return BoardingSlot.at(rescueSeat.slot());
        }
        if (!b.canMakeRoom(body, emergency || rescue)) return BoardingSlot.refused(Refusal.TRANSIENT, "no_room:" + b.lastDeckRefusal());

        // makeRoom uses this exact plan when other riders must move, so its target remains valid when those moves finish.
        var room = deck.planRoom(bodyView, Purpose.BOARD, b.toLocal(body.position()));
        if (room != null) return BoardingSlot.at(room.slot());

        // Only provision relief remains. Use the first seat that the real one-at-a-time relief loop will free, so it cannot
        // spend food and then fail because an earlier seat was still under a larger larder tier.
        int relieved = deck.firstSeatAfterRelievingLarder(bodyView, primary, near, emergency || rescue);
        return relieved < 0 ? BoardingSlot.refused(Refusal.TRANSIENT, "no_room:" + b.lastDeckRefusal()) : BoardingSlot.at(relieved);
    }

    private Built geometry(YukkuriEntity body, Vec3 start) {
        var a = hatA();
        var b = hatB();
        Vec3 end = endPoint(b);
        if (end == null) return new Built(null, Result.refused(Refusal.GEOMETRY, "no_end"));
        BoardingApproach boarding = null;
        if (mode == Mode.HOP && from.kind() == Kind.GROUND && b != null) {
            boarding = boardingApproach(body, b, start, end, plannedStart == null);
            if (boarding == null) return new Built(null, Result.refused(Refusal.GEOMETRY, "no_near_rim_route"));
            takeoffStand = boarding.takeoff();
            start = boarding.takeoff();
        }
        // Keep the precise departure chosen by this preflight; APPROACH must walk to that same seat.
        if (mode == Mode.HOP && a != null && a.isAfloat() && plannedStart == null) {
            var departure = departure(body, a, b, end);
            if (departure == null) return new Built(null, Result.refused(Refusal.GEOMETRY, "no_capable_departure"));
            departureSlot = departure.slot();
            start = departure.position();
        }
        double scale = Math.min(1, body.growthScale() * body.getScale());
        double clearance = Math.max(.03, .02 + .12 * body.getBbHeight());
        var carrier = carrier();
        var carrierHat = carrierHat(carrier);
        int preFrame, postFrame;
        if (from.kind() == Kind.BERTH) preFrame = 1;
        else if (mode == Mode.CLIMB && b != null) preFrame = 2;
        else if (mode == Mode.LIFT && carrierHat != null && b != null && carrierHat == b) preFrame = 2;
        else preFrame = 0;
        if (to.kind() == Kind.BERTH) postFrame = a != null && b == a ? 1 : 2; else postFrame = 0;
        if (preFrame == 2 && b == null) preFrame = 0;
        var worldKnots = new ArrayList<Vec3>();
        var frames = new ArrayList<Integer>();
        var times = new ArrayList<Double>();
        boolean arc = false;
        int moveTicks;
        String curve, entryId = "";
        Vec3 first = start;
        double length;
        switch (mode) {
            case HOP -> {
                var hulls = new ArrayList<HatBoatEntity>();
                if (a != null) hulls.add(a);
                if (b != null && b != a) hulls.add(b);
                var hopEnd = boarding == null ? end : boarding.walk().getFirst();
                var hop = YukkuriLocomotion.hop(body, start, hopEnd, hulls);
                if (!hop.ok()) {
                    if(plannedStart!=null) {
                        var limit=YukkuriLocomotion.limits(body);
                        GameplayLog.limited(body,"aquatic","transfer.takeoff."+hop.reason(),100,
                                "transfer takeoff refused reason={} from={} to={} departureSlot={} horizontal={} rise={} maxReach={} maxHeight={}",
                                hop.reason(),start,end,departureSlot,start.subtract(end).horizontalDistance(),end.y-start.y,limit.maxHopReach(),limit.maxHopHeight());
                    }
                    return new Built(null, Result.refused(Refusal.GEOMETRY, hop.reason()));
                }
                worldKnots.addAll(hop.points());
                for (int i = 0; i < hop.points().size(); i++) {
                    frames.add(i * 2 < hop.points().size() ? preFrame : postFrame);
                    times.add(hop.ticks() * i / (double) (hop.points().size() - 1));
                }
                double time = hop.ticks();
                curve = "hop_rim";
                if (boarding != null) {
                    curve = "hop_board";
                    entryId = boarding.entry();
                    for (int i = 1; i < boarding.walk().size(); i++) {
                        var at = boarding.walk().get(i);
                        time += at.distanceTo(worldKnots.getLast()) / Math.max(.003, YukkuriLocomotion.limits(body).maxHorizSpeed() * .65);
                        worldKnots.add(at); frames.add(postFrame); times.add(time);
                    }
                }
                moveTicks = (int)Math.ceil(time);
                length = YukkuriLocomotion.length(worldKnots);
            }
            case CLIMB -> {
                if (b == null) return new Built(null, Result.refused(Refusal.GEOMETRY, "climb_needs_hull"));
                var layout = b.deckLayout();
                var cls = b.bodyOf(body).cls();
                var entry = nearestEntry(b, start);
                if (entry == null) return new Built(null, Result.refused(Refusal.GEOMETRY, "no_entry"));
                entryId = entry.id();
                var anchor = entry.anchors().get(cls);
                if (anchor == null) return new Built(null, Result.refused(Refusal.GEOMETRY, "no_anchor_for_class"));
                if (anchor.bank() == null) return new Built(null, Result.refused(Refusal.GEOMETRY, "no_water_anchor"));
                curve = anchor.fromWater().isEmpty() ? "climb_rim" : anchor.fromWater();
                var grip = climbGrip(body, b);
                if (grip == null) return new Built(null, Result.refused(Refusal.TRANSIENT, "rim_out_of_reach"));
                var rim = grip.add(0, clearance, 0);
                var hook = new Vec3(start.x, rim.y, start.z);
                if (rim.y - start.y > YukkuriLocomotion.limits(body).maxClimbRise())
                    return new Built(null, Result.refused(Refusal.GEOMETRY, "climb_exceeds_body"));
                worldKnots.add(start);
                frames.add(preFrame);
                worldKnots.add(hook);
                frames.add(preFrame);
                worldKnots.add(rim);
                frames.add(postFrame);
                if(end.y<rim.y-.01){worldKnots.add(new Vec3(end.x,rim.y,end.z));frames.add(postFrame);}
                worldKnots.add(end);
                frames.add(postFrame);
                length = polyLength(worldKnots);
                var clip = CrewClips.of(CrewClips.WATER_HAT_CLIMB);
                moveTicks = YukkuriLocomotion.duration(body, null, YukkuriLocomotion.Kind.CLIMB, length,
                        clip.ticks("pull_end", 31) - clip.ticks("pull_start", 17));
            }
            default -> {
                curve = "lift";
                double rim = Math.max(rimY(a), rimY(b));
                boolean within = a != null && a == b;
                double crest = Math.max(Math.max(start.y, end.y), within || rim == Double.NEGATIVE_INFINITY ? Double.NEGATIVE_INFINITY : rim) + clearance;
                worldKnots.add(start);
                frames.add(preFrame);
                if (crest - start.y > .01) { worldKnots.add(new Vec3(start.x, crest, start.z)); frames.add(preFrame); }
                if (crest - end.y > .01) { worldKnots.add(new Vec3(end.x, crest, end.z)); frames.add(postFrame); }
                worldKnots.add(end);
                frames.add(postFrame);
                length = polyLength(worldKnots);
                var entry = b != null && !within ? nearestEntry(b, start) : a != null && !within ? nearestEntry(a, end) : null;
                if (entry != null) entryId = entry.id();
                var clip = CrewClips.of(CrewClips.BRAID_CRADLE_LIFT);
                int clipMove = clip.ticks("place", 38) - clip.ticks("lift_start", 21);
                moveTicks = YukkuriLocomotion.duration(body, carrier, YukkuriLocomotion.Kind.LIFT, length, clipMove);
            }
        }
        moveTicks = Math.max(4, moveTicks);
        // The block sweep: the body's box along the whole curve against solid blocks (hulls are not blocks; they carry the body).
        var cumulative = new ArrayList<Double>();
        var knots = new ArrayList<CrewState.Knot>();
        double run = 0;
        for (int i = 0; i < worldKnots.size(); i++) {
            if (i > 0) run += worldKnots.get(i).distanceTo(worldKnots.get(i - 1));
            cumulative.add(mode == Mode.HOP ? times.get(i) / moveTicks : run);
            var w = worldKnots.get(i);
            int frame = frames.get(i);
            var local = frame == 0 ? w : (frame == 1 ? a : b).toLocal(w);
            knots.add(new CrewState.Knot(frame, local.x, local.y, local.z));
        }
        if (mode != Mode.HOP) for (int i = 0; i < cumulative.size(); i++)
            cumulative.set(i, run < 1e-9 ? i / (double) Math.max(1, cumulative.size() - 1) : cumulative.get(i) / run);
        else cumulative.set(cumulative.size() - 1, 1.0);
        var geometry = new Geometry(arc, List.copyOf(knots), List.copyOf(cumulative), moveTicks, curve, entryId, start, end, run);
        var swept = sweep(body, geometry, a, b);
        if (swept != null) return new Built(null, Result.refused(Refusal.GEOMETRY, swept));
        if (to.kind() == Kind.GROUND && !SurfaceApproach.supported(body, end)) return new Built(null, Result.refused(Refusal.GEOMETRY, "landing_unsupported"));
        return new Built(geometry, null);
    }

    private record BoardingApproach(Vec3 takeoff, List<Vec3> walk, String entry) {}

    private record DeckNode(int index, double distance) {}
    private record DeckSurface(int nx, int nz, double neighborRange, List<Vec3> points, Map<Long, Boolean> segments) {}

    /** A bounded surface route around real cargo and riders, ending at the already reserved seat. */
    private BoardingApproach boardingApproach(YukkuriEntity body, HatBoatEntity hat, Vec3 from, Vec3 end, boolean approach) {
        var surface = boardingSurface(body, hat);
        if (surface == null) return null;
        int nx = surface.nx(), nz = surface.nz(), finish = nx * nz;
        var points = new ArrayList<>(surface.points()); points.add(end);
        var distance = new double[finish + 1]; Arrays.fill(distance, Double.POSITIVE_INFINITY);
        var next = new int[finish + 1]; Arrays.fill(next, -1);
        var open = new PriorityQueue<DeckNode>(Comparator.comparingDouble(DeckNode::distance));
        distance[finish] = 0; open.add(new DeckNode(finish, 0));
        double neighborRange = surface.neighborRange();
        while (!open.isEmpty()) {
            var node = open.remove(); int at = node.index();
            if (node.distance() > distance[at]) continue;
            int minX = at == finish ? 0 : Math.max(0, at / nz - 1), maxX = at == finish ? nx - 1 : Math.min(nx - 1, at / nz + 1);
            int minZ = at == finish ? 0 : Math.max(0, at % nz - 1), maxZ = at == finish ? nz - 1 : Math.min(nz - 1, at % nz + 1);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                int i = x * nz + z;
                var point = points.get(i);
                if (point == null || i == at || point.subtract(points.get(at)).horizontalDistance() > neighborRange) continue;
                if (at != finish && (Math.abs(i / nz - at / nz) > 1 || Math.abs(i % nz - at % nz) > 1)) continue;
                double cost = distance[at] + point.distanceTo(points.get(at));
                if (cost >= distance[i]) continue;
                long edge = (long)Math.min(i, at) * (finish + 1) + Math.max(i, at);
                Boolean clear = at == finish ? null : surface.segments().get(edge);
                if (clear == null) {
                    clear = deckSegment(body, hat, point, points.get(at));
                    if (at != finish) surface.segments().put(edge, clear);
                }
                if (!clear) continue;
                distance[i] = cost; next[i] = at; open.add(new DeckNode(i, cost));
            }
        }
        var candidates = new ArrayList<Integer>();
        for (int i = 0; i < finish; i++) if (Double.isFinite(distance[i])) candidates.add(i);
        candidates.sort(Comparator.comparingDouble(i -> points.get(i).distanceTo(from)));
        double shortReach = Math.min(hopReach(body), body.getBbWidth() * .65 + .15);
        int tested = 0;
        for (int candidate : candidates) {
            if (++tested > 12) break;
            var landing = points.get(candidate);
            Vec3 takeoff = from;
            if (approach) {
                // Search back from the rim for a dry stand; navigation may route around an obstacle on the way there.
                Vec3 supported = null;
                var towardShore = from.subtract(landing).multiply(1, 0, 1).normalize();
                for (double gap = body.getBbWidth() * .15; gap <= shortReach; gap += .08) {
                    var near = landing.add(towardShore.scale(gap));
                    var floor = SurfaceApproach.floor(body, new Vec3(near.x, from.y, near.z));
                    if (floor != null) { supported = floor; break; }
                }
                if (supported == null || !SurfaceApproach.canApproach(body, supported)) continue;
                takeoff = supported;
            }
            if (takeoff.subtract(landing).horizontalDistance() > shortReach
                    || !YukkuriLocomotion.hop(body, takeoff, landing, List.of(hat)).ok()) continue;
            var walk = new ArrayList<Vec3>(); walk.add(landing);
            for (int at = candidate; at != finish; ) {
                int after = next[at]; if (after < 0) return null;
                var previous = points.get(at); var point = points.get(after);
                if (point.y > previous.y + .002) walk.add(new Vec3(previous.x, point.y, previous.z));
                else if (point.y < previous.y - .002) walk.add(new Vec3(point.x, previous.y, point.z));
                walk.add(point); at = after;
            }
            var entry = nearestEntry(hat, landing);
            return new BoardingApproach(takeoff, List.copyOf(walk), entry == null ? "" : entry.id());
        }
        return null;
    }

    /** Read-only samples are reused only inside this server tick; claims never carry stale world geometry into execution. */
    private DeckSurface boardingSurface(YukkuriEntity body, HatBoatEntity hat) {
        if (deckSurface != null && deckSurfaceTick == level.getGameTime() && deckSurfaceHull == hat
                && hat.position().equals(deckSurfaceOrigin) && hat.getYRot() == deckSurfaceYaw && hat.cargo().count() == deckSurfaceCargo)
            return deckSurface;
        var rim = HatBoatEntity.GripRim.points(hat.mode());
        double lowX = rim.stream().mapToDouble(p -> p.x).min().orElse(0), highX = rim.stream().mapToDouble(p -> p.x).max().orElse(0);
        double lowZ = rim.stream().mapToDouble(p -> p.z).min().orElse(0), highZ = rim.stream().mapToDouble(p -> p.z).max().orElse(0);
        double spacing = Math.max(.10, Math.min(.18, body.getBbWidth() * .3)) / hat.unit();
        int nx = (int)Math.ceil((highX - lowX) / spacing) + 1, nz = (int)Math.ceil((highZ - lowZ) / spacing) + 1;
        if (nx * nz > 900) return null;
        deckObstacles = level.getEntitiesOfClass(YukkuriEntity.class, hat.getBoundingBox().inflate(4), y -> y != body && y.isAlive())
                .stream().map(YukkuriEntity::getBoundingBox).toList();
        var points = new ArrayList<Vec3>();
        for (int x = 0; x < nx; x++) for (int z = 0; z < nz; z++)
            points.add(deckFoot(body, hat, lowX + x * spacing, lowZ + z * spacing));
        deckSurfaceTick = level.getGameTime(); deckSurfaceHull = hat; deckSurfaceOrigin = hat.position();
        deckSurfaceYaw = hat.getYRot(); deckSurfaceCargo = hat.cargo().count();
        return deckSurface = new DeckSurface(nx, nz, spacing * hat.unit() * 1.5, points, new HashMap<>());
    }

    private Vec3 deckFoot(YukkuriEntity body, HatBoatEntity hat, double x, double z) {
        if (!Double.isFinite(HatVessel.surface(hat.mode(), x, z))) return null;
        double surface = HatVessel.travelSupport(hat.mode(), x, z, HatVessel.radius(body.getBbWidth()) / hat.unit());
        if (!Double.isFinite(surface)) return null;
        var at = hat.toWorld(new Vec3(x, surface, z));
        var box = body.getBoundingBox().move(at.subtract(body.position())).deflate(.002);
        if (level.getBlockCollisions(body, box).iterator().hasNext()
                || deckObstacles.stream().anyMatch(box::intersects)) return null;
        if (hat.cargo().count() > 0) for (var zone : hat.capsizeSpillZones()) {
            var cargo = hat.toWorld(zone.position());
            double gap = zone.radius() * hat.unit() + body.getBbWidth() * .5;
            if (at.subtract(cargo).horizontalDistance() < gap && at.y < cargo.y + zone.height() * hat.unit() - .002
                    && at.y + body.getBbHeight() > cargo.y) return null;
        }
        return at;
    }

    private boolean deckSegment(YukkuriEntity body, HatBoatEntity hat, Vec3 from, Vec3 to) {
        if (Math.abs(to.y - from.y) > body.maxUpStep() + .002) return false;
        for (int i = 1; i <= 4; i++) {
            var local = hat.toLocal(from.lerp(to, i / 4.0));
            var actual = deckFoot(body, hat, local.x, local.z);
            if (actual == null || actual.y > Math.max(from.y, to.y) + .002) return false;
        }
        return true;
    }

    private record Departure(int slot, Vec3 position) {}
    private Departure departure(YukkuriEntity body, HatBoatEntity source, HatBoatEntity destination, Vec3 end) {
        var placed = source.deck().get(bodyId);
        if (placed == null || !placed.aboard()) return null;
        var hulls = destination == null || destination == source ? List.of(source) : List.of(source, destination);
        if (YukkuriLocomotion.hop(body, body.position(), end, hulls).ok())
            return new Departure(placed.slot(), body.position());
        var view = source.bodyOf(body);
        int tested = 0;
        for (int seat : source.deck().freeSeats(view, Purpose.SERVICE, source.toLocal(end))) {
            if (++tested > 24) break;
            var candidate = source.toWorld(source.deckLayout().slot(seat).pos());
            boolean walks = placed.slot() >= 0 && source.deckLayout().path(placed.slot(), seat, view.cls(), false, null) != null;
            if (!walks && !YukkuriLocomotion.hop(body, body.position(), candidate, List.of(source)).ok()) continue;
            if (YukkuriLocomotion.hop(body, candidate, end, hulls).ok()) return new Departure(seat, candidate);
        }
        return null;
    }

    private Vec3 endPoint(HatBoatEntity b) {
        if (to.kind() == Kind.BERTH) {
            if (b == null) return null;
            int s = slot >= 0 ? slot : to.slot();
            return s < 0 ? b.toWorld(b.deckLayout().slot(b.deckLayout().firstOfKind(DeckLayout.Kind.BRIM)).pos()) : b.toWorld(b.deckLayout().slot(s).pos());
        }
        return to.pos();
    }

    private static double rimY(HatBoatEntity hat) { return hat == null ? Double.NEGATIVE_INFINITY : hat.getY() + hat.deckLayout().top * hat.unit(); }

    static DeckLayout.Entry nearestEntry(HatBoatEntity hat, Vec3 world) {
        var local = hat.toLocal(world);
        DeckLayout.Entry best = null;
        double cost = Double.MAX_VALUE;
        for (var entry : hat.deckLayout().entries()) {
            double d = Math.hypot(entry.edge().x - local.x, entry.edge().z - local.z);
            if (d < cost) { cost = d; best = entry; }
        }
        return best;
    }

    private static double polyLength(List<Vec3> points) {
        double sum = 0;
        for (int i = 1; i < points.size(); i++) sum += points.get(i).distanceTo(points.get(i - 1));
        return sum;
    }

    /** Null when the body's box clears every block all along the curve; else why not. */
    private String sweep(YukkuriEntity body, Geometry g, HatBoatEntity a, HatBoatEntity b) {
        int samples = Math.max(4, (int) Math.ceil(g.length() / .04));
        var box = body.getBoundingBox();
        Vec3 origin = body.position();
        for (int i = 0; i <= samples; i++) {
            double u = i / (double) samples;
            var at = sample(g, a, b, u);
            if (at == null) return "hull_unloaded";
            if (!level.hasChunkAt(BlockPos.containing(at)) || !level.getWorldBorder().isWithinBounds(BlockPos.containing(at))) return "unloaded";
            var moved = box.move(at.subtract(origin)).deflate(.002);
            if (level.getBlockCollisions(body, moved).iterator().hasNext()) return "blocked_at_" + String.format(Locale.ROOT, "%.2f", u);
        }
        return null;
    }

    private static Vec3 sample(Geometry g, HatBoatEntity a, HatBoatEntity b, double u) {
        if (g.arc()) {
            var p0 = g.knots().getFirst();
            var p1 = g.knots().getLast();
            var from = world(p0, a, b);
            var to = world(p1, a, b);
            return from == null || to == null ? null : HatVessel.arc(from, to, g.moveTicks(), Math.clamp(u, 0, 1) * g.moveTicks());
        }
        double e = g.curve().startsWith("hop_") ? Math.clamp(u, 0, 1) : CrewState.ease(u);
        for (int i = 0; i + 1 < g.knots().size(); i++) {
            double lo = g.cumulative().get(i), hi = g.cumulative().get(i + 1);
            if (e <= hi || i + 2 == g.knots().size()) {
                var p0 = world(g.knots().get(i), a, b);
                var p1 = world(g.knots().get(i + 1), a, b);
                return p0 == null || p1 == null ? null : p0.lerp(p1, hi - lo < 1e-9 ? 1 : Math.clamp((e - lo) / (hi - lo), 0, 1));
            }
        }
        return world(g.knots().getFirst(), a, b);
    }
    private static Vec3 world(CrewState.Knot k, HatBoatEntity a, HatBoatEntity b) {
        if (k.frame() == 0) return k.vec();
        var hat = k.frame() == 1 ? a : b;
        return hat == null ? null : hat.toWorld(k.vec());
    }

    /**
     * The carrier's stand and heading from which the braid (or, one step down, the mouth) reaches the body along the whole curve.
     * A boat carrier may walk the deck to another seat; any other carrier stands where she is and turns. Cheapest first.
     */
    private Stand chooseStand(YukkuriEntity body, YukkuriEntity carrier, Geometry g) {
        var hat = carrierHat(carrier);
        var candidates = new ArrayList<Integer>();
        int here = DeckLayout.NONE;
        if (hat != null) {
            var placed = hat.deck().get(carrier.getUUID());
            if (placed != null && placed.aboard() && placed.slot() >= 0) { here = placed.slot(); candidates.add(here); }
            var pb = hat.bodyOf(carrier);
            for (int seat : hat.deck().freeSeats(pb, Purpose.SERVICE, hat.toLocal(carrier.position()))) if (!candidates.contains(seat)) candidates.add(seat);
            for (int seat : hat.deck().freeSeats(pb, Purpose.RESCUE, hat.toLocal(carrier.position()))) if (!candidates.contains(seat)) candidates.add(seat);
        }
        boolean braid = CrewReach.hasBraid(carrier);
        for (boolean mouth : braid ? new boolean[]{false, true} : new boolean[]{true}) {
            Stand best = null;
            double cost = Double.MAX_VALUE;
            if (hat == null) {
                var probe = tryHeadings(body, carrier, g, carrier.position(), DeckLayout.NONE, mouth);
                if (probe != null) return probe;
                if (!carrier.isInWaterOrBubble()) {
                    // Both the pickup and set-down must be in reach from the same supported, walkable stand.
                    // The old placement stand may be on the far side of the hat after a stem delivery.
                    var middle = g.start().lerp(g.end(), .5);
                    double ring = carrier.getBbWidth() * .5 + body.getBbWidth() * .5 + .08;
                    for (double distance : new double[]{ring, ring + .25, ring + .5}) for (int i = 0; i < 16; i++) {
                        double angle = i * Math.PI / 8;
                        var near = new Vec3(middle.x + Math.cos(angle) * distance, carrier.getY(), middle.z + Math.sin(angle) * distance);
                        var feet = CrewTransfers.standingGround(level, near, carrier);
                        if (feet == null) continue;
                        var found = tryHeadings(body, carrier, g, feet, DeckLayout.NONE, mouth);
                        if (found == null) continue;
                        double route = SurfaceApproach.routeLength(carrier, feet, body.getBoundingBox().move(g.start().subtract(body.position())));
                        if (Double.isFinite(route) && route < cost) { cost = route; best = found; }
                    }
                    if (best != null) return best;
                }
                continue;
            }
            for (int seat : candidates) {
                var feet = hat.toWorld(hat.deckLayout().slot(seat).pos());
                var found = tryHeadings(body, carrier, g, feet, seat, mouth);
                if (found == null) continue;
                // A reachable braid is useless when the carrier cannot actually walk to that stand.
                if (seat != here) {
                    boolean walks = here >= 0 && hat.deckLayout().path(here, seat, pbClass(carrier, hat), false,
                            s -> hat.deck().occupant(s) != null && !carrierId.equals(hat.deck().occupant(s))) != null;
                    if (!walks && !YukkuriLocomotion.hop(carrier, carrier.position(), feet, List.of(hat)).ok()) continue;
                }
                double c = (seat == here ? 0 : 1 + feet.distanceTo(carrier.position())) + Math.abs(Mth.wrapDegrees(found.yaw() - carrier.yBodyRot)) / 360.0;
                if (c < cost) { cost = c; best = found; }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static BodyClass pbClass(YukkuriEntity carrier, HatBoatEntity hat) { return hat.bodyOf(carrier).cls(); }

    private Stand tryHeadings(YukkuriEntity body, YukkuriEntity carrier, Geometry g, Vec3 feet, int seat, boolean mouth) {
        for (var refused : refusedStands) if (refused.distanceToSqr(feet) < .0225) return null;
        Stand best = null;
        double cost = Double.MAX_VALUE;
        var a = hatA();
        var b = hatB();
        for (int step = 0; step < 24; step++) {
            float yaw = step * 15f;
            var anchor = mouth ? CrewReach.mouthAt(carrier, feet, yaw) : CrewReach.rootAt(carrier, feet, yaw);
            boolean ok = true;
            for (int i = 0; i <= 8 && ok; i++) {
                var at = sample(g, a, b, i / 8.0);
                if (at == null) { ok = false; break; }
                var contactPoint = CrewReach.contactOn(body, at, anchor);
                ok = mouth ? CrewReach.mouthReaches(carrier, feet, yaw, anchor, contactPoint)
                        : CrewReach.braidReaches(carrier, feet, yaw, anchor, contactPoint);
            }
            if (!ok) continue;
            double workYaw = SurfaceApproach.yaw(feet, g.start().lerp(g.end(), .5));
            double c = Math.abs(Mth.wrapDegrees(yaw - (float)workYaw))
                    + .05 * Math.abs(Mth.wrapDegrees(yaw - carrier.yBodyRot));
            if (c < cost) { cost = c; best = new Stand(seat, feet, yaw, mouth); }
        }
        return best;
    }

    // ================================================================= claiming
    /** Takes the destination seat (and a lifted body's reserved seat inside a hull); null when taken, else the refusal. */
    private Result claim() {
        var body = body();
        var carrier = carrier();
        var b = hatB();
        var a = hatA();
        if (to.kind() != Kind.BERTH) { loadBefore = loadAfter = a == null ? 0 : a.capacityUsed(); return null; }
        loadBefore = b.capacityUsed();
        boolean within = a != null && a == b;
        if (within) {
            var bodyDeck = b.bodyOf(body);
            var previousLease = b.deck().lease(bodyId);
            if (!b.deck().reserveLift(bodyDeck, to.slot(), Purpose.SERVICE)) return Result.refused(Refusal.TRANSIENT, "seat_refused:" + b.deck().lastRefusal());
            var lease = b.deck().lease(bodyId);
            if (lease != null && (previousLease == null || lease.id() != previousLease.id())) claimedLease = lease.id();
            slot = to.slot();
            claimed = true;
            loadAfter = b.capacityUsed();
            return null;
        }
        var near = body.position();
        Vec3 seatLocal = null;
        boolean owner = body.getUUID().equals(b.ownerYukkuri());
        if (!owner && !b.makeRoom(body, emergency || rescue)) return Result.refused(Refusal.TRANSIENT, "no_room:" + b.lastDeckRefusal());
        seatLocal = switch (purpose) {
            case OWN -> b.reserveOwnBoardingSeat(body, near, at -> true, slot);
            case RETAINED -> b.returnSeat(body, emergency, slot);
            case RESCUE -> b.reserveRescueBrim(body, near, slot);
            default -> b.reserveBoardingSeat(body, near, emergency, slot);
        };
        if (seatLocal == null) {
            var a2 = b.admission(body, purpose == Purpose.OWN ? Purpose.OWN : Purpose.BOARD);
            return switch (a2.reason()) {
                case UNITS_FULL, NOT_ADMITTED -> Result.refused(Refusal.INCAPABLE, a2.text());
                case NO_SLOT_FOR_CLASS -> Result.refused(Refusal.GEOMETRY, a2.text());
                default -> Result.refused(Refusal.TRANSIENT, "no_seat:" + a2.text());
            };
        }
        slot = b.deckLayout().slotAt(seatLocal, .02);
        if (slot < 0) { b.cancelIncoming(body); return Result.refused(Refusal.GEOMETRY, "seat_not_on_deck"); }
        claimed = true;
        loadAfter = b.capacityUsed();
        return null;
    }

    private void releaseClaim(YukkuriEntity body, boolean success) {
        var b = hatB();
        if (!claimed || b == null || body == null || success) return;
        if (a_withinBoat()) {
            b.deck().abandon(body.getUUID(), "transfer_failed");
            b.deck().lift(body.getUUID(), false);
            var lease = b.deck().lease(bodyId);
            if (lease != null && lease.id() == claimedLease) b.deck().release(bodyId, "transfer_failed");
        } else if (!b.hasPassenger(body)) {
            b.cancelIncoming(body);
            if (purpose == Purpose.OWN) b.releaseOwnBoardingSeat(body);
        }
        b.syncSeats();
    }
    private boolean a_withinBoat() { return boatA >= 0 && boatA == boatB; }

    /** A seat promised to a body that is still on its way stays promised while the ticket lives. */
    private void renewClaim() {
        var b = hatB();
        if (!claimed || arrived || b == null || slot < 0) return;
        if (!b.deck().renewTarget(bodyId, slot, HatDeck.BOARD_TTL) && !reverting) fail(Refusal.TRANSIENT, "seat_claim_lost");
    }

    private void start() {
        var body = body();
        var carrier = carrier();
        phase = Phase.APPROACH;
        phaseStart = lastTick = level.getGameTime();
        bestProgressAt = phaseStart;
        body.family().crew().take(this);
        if (carrier != null) carrier.family().crew().take(this);
        var a = hatA();
        var b = hatB();
        if (a != null) a.crew().attach(this);
        if (b != null && b != a) b.crew().attach(this);
        var ch = carrierHat(carrier);
        if (ch != null && ch != a && ch != b) ch.crew().attach(this);
        if (carrier != null && mode == Mode.LIFT) { carrier.life().wakeInHat(); body.life().wakeInHat(); }
        if (rescue && carrier != null && !carrier.life().spendEnergy(.15f)) {
            fail(Refusal.INCAPABLE, "energy_unavailable");
            return;
        }
        plannedStart = body.position();
        publish();
        GameplayLog.detail(body, "crew.open." + body.getId(), 0, mode + " " + from.key() + " -> " + to.key() + " slot=" + slot + " why=" + why);
    }

    // ================================================================= the tick
    /** Advance once per game tick, from whichever participant's goal runs first. */
    public void tick() {
        if (finished) return;
        long now = level.getGameTime();
        if (lastTick == now) return;
        lastTick = now;
        ticksTotal++;
        var body = body();
        var carrier = carrier();
        if (body == null || !body.isAlive() || carrierId != null && (carrier == null || !carrier.isAlive())) { fail(Refusal.TRANSIENT, "participant_gone"); return; }
        if (body.isNoAi() || body.isCommandPlaying() || body.operations().active() || carrier != null && (carrier.isNoAi() || carrier.isCommandPlaying() || carrier.operations().active())) {
            fail(Refusal.TRANSIENT, "participant_interrupted");
            return;
        }
        var a = hatA();
        var b = hatB();
        if (boatA >= 0 && a == null || boatB >= 0 && b == null) { fail(Refusal.TRANSIENT, "hull_unloaded"); return; }
        for (var hat : new HatBoatEntity[]{a, b}) {
            if (hat == null) continue;
            if (hat.isRemoved() || hat.capsizing() || hat.state() == HatBoatEntity.State.CAPSIZED || hat.state() == HatBoatEntity.State.RESCUED) { fail(Refusal.TRANSIENT, "hull_capsizing"); return; }
        }
        if (now - phaseStart > ttl()) { fail(Refusal.TRANSIENT, "phase_timeout_" + phase.name().toLowerCase(Locale.ROOT)); return; }
        watchdog(body, carrier, now);
        if (finished) return;
        if (phase.ordinal() >= Phase.CONTACT.ordinal() && phase.ordinal() <= Phase.MOVE.ordinal() && !reverting
                && (a != null && a.unsteadyMotion() || b != null && b.unsteadyMotion())) { slip("hull_shaken"); return; }
        holdHulls(carrier);
        if (now % 50 == 0) renewClaim();
        if (finished) return;
        prevBodyYaw = bodyYaw;
        switch (phase) {
            case CLAIM -> next(Phase.APPROACH);
            case APPROACH -> approach(body, carrier, now);
            case CONTACT -> contact(body, carrier, now);
            case MOVE -> move(body, carrier, now);
            case SETTLE -> settle(body, carrier, now);
            case RELEASE -> release(body, carrier, now);
        }
        if (!finished) {
            if (mode != Mode.LIFT && phase.ordinal() >= Phase.MOVE.ordinal()) sampleBody(body);
            publish();
        }
    }

    private int ttl() {
        return switch (phase) {
            case CLAIM -> CLAIM_TTL;
            case APPROACH -> mode == Mode.HOP ? 400 : mode == Mode.LIFT ? 260 : 120;
            case CONTACT -> CONTACT_TTL;
            case MOVE -> (geometry == null ? 100 : geometry.moveTicks()) * 3 + STALL_TICKS;
            case SETTLE -> SETTLE_TTL;
            case RELEASE -> RELEASE_TTL;
        };
    }

    /** No progress for 200 ticks ends the ticket; progress is the distance still to cover in this phase or the phase itself. */
    private void watchdog(YukkuriEntity body, YukkuriEntity carrier, long now) {
        double metric = phase.ordinal() * -1000.0;
        if (phase == Phase.APPROACH) {
            var mover = driver();
            var target = stand != null && mode == Mode.LIFT && stand.feet() != null ? stand.feet() : geometry != null ? geometry.end() : body.position();
            metric += mover == null ? 0 : mover.position().distanceTo(target);
            if (stand != null && carrier != null) metric += Math.abs(Mth.wrapDegrees(stand.yaw() - carrier.yBodyRot)) / 180;
        } else if (phase == Phase.MOVE) {
            metric -= movedDistance;
        }
        if (metric < bestProgress - 1e-3) { bestProgress = metric; bestProgressAt = now; }
        else if (now - bestProgressAt > STALL_TICKS) {
            if (mode == Mode.LIFT) BraidCarry.stalled(body, carrier, stand == null ? null : stand.feet(), stand == null ? 0 : stand.yaw(), phase.name());
            GameplayLog.limited(body, "aquatic", "transfer.stall." + phase, 200, "transfer stalled mode={} phase={} metric={} body={}", mode, phase, metric, bodyId);
            fail(Refusal.TRANSIENT, "stalled_" + phase.name().toLowerCase(Locale.ROOT));
        }
    }

    private void next(Phase p) {
        phase = p;
        phaseStart = level.getGameTime();
        bestProgress = Double.MAX_VALUE;
        bestProgressAt = phaseStart;
        if (p == Phase.CONTACT) {
            contactStart = phaseStart;
            if (mode == Mode.LIFT) {
                var body = body();
                var carrier = carrier();
                bodyYaw = prevBodyYaw = body.yBodyRot;
                contact = stand != null && stand.mouth() ? Contact.MOUTH_BITE : Contact.BRAID_CRADLE;
                updateLiftContact(body, carrier, 0);
                GameplayLog.event(body, "aquatic", "transfer contact started mode={} contact={} body={} carrier={} frame={} feet={} at={}",
                        mode, contact, bodyId, carrierId, contactFrame, GameplayLog.at(body.position()),
                        GameplayLog.at(contactWorld(contactAt)));
            }
        }
        if (p == Phase.CONTACT && mode != Mode.HOP) bodyHeld = true;
        if (p == Phase.RELEASE) { contact = Contact.NONE; contactAt = null; }
        GameplayLog.detail(body(), "crew.phase." + id, 0, "-> " + p + " mode=" + mode);
    }

    private void holdHulls(YukkuriEntity carrier) {
        var holder = driver();
        if (holder == null) return;
        var purposeOfHold = rescue || mode != Mode.HOP ? HullHelm.Purpose.RESCUE : HullHelm.Purpose.BOARDING;
        for (var hat : new HatBoatEntity[]{hatA(), hatB(), carrierHat(carrier)}) if (hat != null) hat.helm().hold(holder, purposeOfHold);
    }

    // ---------------------------------------------------------------- APPROACH
    private void approach(YukkuriEntity body, YukkuriEntity carrier, long now) {
        switch (mode) {
            case HOP -> approachHop(body, now);
            case CLIMB -> approachClimb(body, now);
            case LIFT -> approachLift(body, carrier, now);
        }
    }

    private void approachHop(YukkuriEntity body, long now) {
        var a = hatA();
        var b = hatB();
        // A hat standing on the ground or floated at a bank to catch young has no walking deck: its rider hops from where it sits.
        if (a != null && a.isAfloat()) {
            var toward = endPoint(b);
            if (toward == null) { fail(Refusal.GEOMETRY, "no_end"); return; }
            if (a.changingSeat(body)) return;
            var placed = a.deck().get(bodyId);
            if (placed == null) { fail(Refusal.GEOMETRY, "no_departure_seat"); return; }
            if (departureSlot >= 0 && placed.slot() != departureSlot) {
                var view = a.bodyOf(body);
                boolean walk = placed.slot() >= 0 && a.deckLayout().path(placed.slot(), departureSlot, view.cls(), false, null) != null;
                if (!a.deck().moveWithin(view, departureSlot, a.toLocal(body.position()), !walk, Purpose.SERVICE)) {
                    if (++approachTries >= 40) fail(Refusal.GEOMETRY, "departure_seat_unavailable");
                } else {
                    a.syncSeats();
                    GameplayLog.event(body, "aquatic", "transfer departure hat={} slot={} world={} destination={}",
                            a.getUUID(), departureSlot, a.toWorld(a.deckLayout().slot(departureSlot).pos()), toward);
                }
                return;
            }
        } else if (a == null) {
            // Walk all the way to the checked near-rim stand; maximum jumping range is not an arrival tolerance.
            if (takeoffStand == null) { fail(Refusal.GEOMETRY, "no_takeoff"); return; }
            if (!SurfaceApproach.arrived(body, takeoffStand) && !SurfaceApproach.walk(body, takeoffStand, .9)) return;
            SurfaceApproach.halt(body);
        }
        var plannedEnd = geometry == null ? endPoint(b) : geometry.end();
        float yaw = SurfaceApproach.yaw(body.position(), plannedEnd);
        SurfaceApproach.face(body, yaw);
        bodyYaw = prevBodyYaw = body.yBodyRot;
        if (Math.abs(Mth.wrapDegrees(body.yBodyRot - yaw)) > 12) return;
        begin(body, now);
    }

    private static double hopReach(YukkuriEntity body) {
        return YukkuriLocomotion.limits(body).maxHopReach();
    }

    /** The decision layer uses the same real entry distance as opening and approaching a climb. */
    public static boolean climbInReach(YukkuriEntity body, HatBoatEntity hat) {
        return climbGrip(body, hat) != null;
    }

    /** The same physical lip and mouth geometry used by self rescue; jumping range is irrelevant to a bite. */
    public static Vec3 climbGrip(YukkuriEntity body, HatBoatEntity hat) {
        Vec3 best = null;
        double closest = Double.POSITIVE_INFINITY;
        for (var local : HatBoatEntity.GripRim.points(hat.mode())) {
            var lip = hat.toWorld(local);
            float yaw = SurfaceApproach.yaw(body.position(), lip);
            var mouth = CrewReach.mouthAt(body, body.position(), yaw);
            double wall = Math.max(.01 * Math.min(1, body.growthScale() * body.getScale()), lip.y - hat.getY())
                    + BraidRescueGeometry.biteAllowance(body);
            double gap = BraidRescueGeometry.rimDistance(mouth, lip, wall);
            if (gap < closest && BraidRescueGeometry.holds(body, mouth, lip, wall) && body.hasLineOfSight(hat)) {
                closest = gap; best = lip;
            }
        }
        return best;
    }

    private void approachClimb(YukkuriEntity body, long now) {
        var b = hatB();
        var rim = climbGrip(body, b);
        if (rim == null) {
            if (now - phaseStart > 60) fail(Refusal.GEOMETRY, "rim_out_of_reach");
            return;
        }
        body.getNavigation().stop();
        float yaw = SurfaceApproach.yaw(body.position(), rim);
        SurfaceApproach.face(body, yaw);
        if (Math.abs(Mth.wrapDegrees(body.yBodyRot - yaw)) > 15) return;
        begin(body, now);
    }

    private void approachLift(YukkuriEntity body, YukkuriEntity carrier, long now) {
        if (stand == null) { fail(Refusal.GEOMETRY, "no_stand"); return; }
        var hat = carrierHat(carrier);
        body.getNavigation().stop();
        if (hat != null && stand.seat() >= 0) {
            var placed = hat.deck().get(carrier.getUUID());
            if (placed == null || !placed.aboard()) { fail(Refusal.TRANSIENT, "carrier_not_seated"); return; }
            if (placed.slot() != stand.seat() && !hat.deck().moving(carrier.getUUID())) {
                var previousLease = hat.deck().lease(carrierId);
                boolean walks = placed.slot() >= 0 && hat.deckLayout().path(placed.slot(), stand.seat(), hat.bodyOf(carrier).cls(), false,
                        s -> hat.deck().occupant(s) != null && !carrierId.equals(hat.deck().occupant(s))) != null;
                if (!hat.deck().moveWithin(hat.bodyOf(carrier), stand.seat(), hat.toLocal(carrier.position()), !walks, Purpose.SERVICE)) {
                    if (++approachTries >= 12) fail(Refusal.GEOMETRY, "stand_unreachable:" + hat.deck().lastRefusal());
                    return;
                }
                var lease = hat.deck().lease(carrierId);
                if (lease != null && (previousLease == null || lease.id() != previousLease.id())) carrierLease = lease.id();
                hat.syncSeats();
                return;
            }
            if (hat.deck().moving(carrier.getUUID()) || placed.slot() != stand.seat()) return;
        } else if (!carrier.isInWaterOrBubble() && !SurfaceApproach.arrived(carrier, stand.feet())) {
            boolean arrived = SurfaceApproach.walkAround(carrier, stand.feet(), body, .8);
            BraidCarry.approach(body, carrier, stand.feet(), stand.yaw(), "carrier_walk");
            if (!arrived) {
                var result = SurfaceApproach.lastResult(carrier, stand.feet());
                if (result == SurfaceApproach.Result.BLOCKED) {
                    if (blockedStandSince == 0) blockedStandSince = now;
                    if (now - blockedStandSince >= 20) {
                        refusedStands.add(stand.feet()); blockedStandSince = 0;
                        if (refusedStands.size() > 2) { fail(Refusal.GEOMETRY, "carrier_route_blocked"); return; }
                        var again = plan();
                        if (again != null) fail(again.refusal(), "carrier_replan:" + again.reason());
                    }
                } else blockedStandSince = 0;
                return;
            }
        }
        carrier.getNavigation().stop();
        carrier.setSpeed(0);
        SurfaceApproach.face(carrier, stand.yaw());
        if (Math.abs(Mth.wrapDegrees(carrier.yBodyRot - stand.yaw())) > 1.5f) {
            BraidCarry.approach(body, carrier, stand.feet(), stand.yaw(), "carrier_turn");
            return;
        }
        // The body must still be where the plan started; a swimmer that drifted is planned again from where it is.
        if (plannedStart != null && body.position().distanceToSqr(plannedStart) > .0016) {
            plannedStart = null;
            var again = plan();
            if (again != null) { fail(again.refusal(), "replan:" + again.reason()); return; }
            return;
        }
        if (!reachesNow(body, carrier, 0)) {
            if (standStarts++ < 2) { var again = plan(); if (again != null) fail(again.refusal(), again.reason()); return; }
            fail(Refusal.GEOMETRY, "out_of_reach_after_stand");
            return;
        }
        float toCarrier = SurfaceApproach.yaw(body.position(), carrier.position());
        SurfaceApproach.face(body, toCarrier);
        begin(body, now);
    }

    private void begin(YukkuriEntity body, long now) {
        // The hull's single MOVE is taken at the moment the contact closes; a second transfer waits in CONTACT.
        plannedStart = body.position();
        var again = geometry(body);
        if (again.failure() != null) { fail(again.failure().refusal(), again.failure().reason()); return; }
        geometry = again.geometry();
        if (mode == Mode.HOP) {
            if (!acquireMove(now)) { next(Phase.CONTACT); return; }
            startMove(body, now);
            return;
        }
        next(Phase.CONTACT);
    }

    // ---------------------------------------------------------------- CONTACT
    private void contact(YukkuriEntity body, YukkuriEntity carrier, long now) {
        long age = now - phaseStart;
        body.getNavigation().stop();
        body.setDeltaMovement(Vec3.ZERO);
        if (carrier != null) { carrier.getNavigation().stop(); carrier.setSpeed(0); }
        int length = contactTicks();
        if (mode == Mode.CLIMB) {
            var clip = CrewClips.of(CrewClips.WATER_HAT_CLIMB);
            contact = age >= clip.ticks("hook_contact", 15) ? Contact.BRAID_HOOK : Contact.MOUTH_BITE;
            var b = hatB();
            var grip = climbGrip(body, b);
            if (grip == null) { slip("rim_lost"); return; }
            setContact(b, b.toLocal(grip), 2);
        } else if (mode == Mode.LIFT) {
            contact = stand != null && stand.mouth() ? Contact.MOUTH_BITE : Contact.BRAID_CRADLE;
            updateLiftContact(body, carrier, 0);
            if (!reachesNow(body, carrier, 0)) { slip("contact_lost"); return; }
            SurfaceApproach.face(carrier, stand.yaw());
        }
        if (mode == Mode.CLIMB) {
            var b = hatB();
            var rim = climbGrip(body, b);
            if (rim == null) { slip("rim_lost"); return; }
            float yaw = SurfaceApproach.yaw(body.position(), rim);
            SurfaceApproach.face(body, yaw);
        }
        bodyYaw = body.yBodyRot;
        if (age < length) return;
        if (!acquireMove(now)) return;
        startMove(body, now);
    }

    private int contactTicks() {
        return switch (mode) {
            case LIFT -> stand != null && stand.mouth() ? 5 : CrewClips.of(CrewClips.BRAID_CRADLE_LIFT).ticks("lift_start", 21);
            case CLIMB -> CrewClips.of(CrewClips.WATER_HAT_CLIMB).ticks("pull_start", 17);
            case HOP -> 0;
        };
    }

    private boolean acquireMove(long now) {
        for (var hat : new HatBoatEntity[]{hatA(), hatB()}) {
            if (hat == null) continue;
            var other = hat.crew().mover();
            if (other != null && other != this) return false;
        }
        holdsMove = true;
        return true;
    }

    private void startMove(YukkuriEntity body, long now) {
        // From here the geometry is the one that was just built from the real positions.
        moveStart = now;
        revertAt = -1;
        next(Phase.MOVE);
        bodyHeld = true;
        // A frame describes geometry, not completed boarding. Keep the origin until the actual endpoint is reached.
        if (body.getVehicle() instanceof HatBoatEntity hull) body.family().crew().rememberFrame(hull);
        if (mode == Mode.LIFT && boatA >= 0 && boatA == boatB) hatB().deck().lift(bodyId, true);
        if (!finished) {
            GameplayLog.detail(body, "crew.move." + id, 0, mode + " ticks=" + geometry.moveTicks() + " curve=" + geometry.curve() + " entry=" + geometry.entryId());
            var limits = YukkuriLocomotion.limits(mode == Mode.LIFT && carrier() != null ? carrier() : body);
            double peak = geometry.knots().stream().map(k -> world(k, hatA(), hatB())).filter(Objects::nonNull)
                    .mapToDouble(p -> p.y - geometry.start().y).max().orElse(0);
            GameplayLog.event(body, "aquatic", "transfer motion mode={} ticks={} peak={} maxHopHeight={} maxHopReach={} maxSpeed={} body={} carrier={} from={} to={} departureSlot={}",
                    mode, geometry.moveTicks(), peak, limits.maxHopHeight(), limits.maxHopReach(), limits.maxHorizSpeed(), bodyId, carrierId,
                    geometry.start(),geometry.end(),departureSlot);
        }
    }

    // ---------------------------------------------------------------- MOVE
    private void move(YukkuriEntity body, YukkuriEntity carrier, long now) {
        var current = entry != null ? entry : buildEntry();
        double u = current.progress(now);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0;
        body.getNavigation().stop();
        if (carrier != null) { carrier.getNavigation().stop(); carrier.setSpeed(0); }
        var position = current.position(level, now);
        if (position == null) { fail(Refusal.TRANSIENT, "hull_unloaded"); return; }
        var actor = mode == Mode.LIFT && carrier != null ? carrier : body;
        double speed = YukkuriLocomotion.limits(actor).maxHorizSpeed();
        if (body.getVehicle() instanceof HatBoatEntity hull) YukkuriLocomotion.carryWithHull(body, hull);
        var before = body.position();
        var step = YukkuriLocomotion.advance(body, position, speed);
        movedDistance += before.distanceTo(body.position());
        if (rimBoarded && body.getVehicle() == hatB()) hatB().deck().moveFree(bodyId, hatB().toLocal(body.position()));
        if(mode==Mode.LIFT&&carrier!=null){
            updateLiftContact(body,carrier,u);
            if(!reverting&&!carrier.life().spendEnergy(.02f)){fail(Refusal.INCAPABLE,"energy_unavailable");return;}
            if(!reverting&&!reachesNow(body,carrier,u)){slip("contact_lost");return;}
        }else if(mode==Mode.CLIMB&&!reverting&&!body.life().spendEnergy(.04f)){
            fail(Refusal.INCAPABLE,"energy_unavailable");return;
        }
        blockedMoveTicks = step.blocked() ? blockedMoveTicks + 1 : 0;
        if (!step.arrived()) {
            // Pause the synchronized curve clock while a real, speed-limited step catches up.
            if (reverting) revertAt++; else moveStart++;
            if (blockedMoveTicks >= 5) {
                GameplayLog.event(body, "aquatic", "transfer collision mode={} phase={} actual={} target={} requested={} allowed={} moved={} remaining={} source={} destination={}",
                        mode, reverting ? "REVERT" : "MOVE", body.position(), position, step.requested(), step.allowed(), step.moved(), step.remaining(), boatA, boatB);
                if (reverting) finish(false, Refusal.GEOMETRY, "revert_collision:" + pendingReason);
                else fail(Refusal.GEOMETRY, "move_collision");
            }
            return;
        }
        if (!reverting && mode == Mode.HOP && geometry.curve().equals("hop_board") && !rimBoarded
                && current.progress(now) >= geometry.cumulative().get(32)) {
            var landing = world(geometry.knots().get(32), hatA(), hatB());
            if (landing == null || body.position().distanceToSqr(landing) > .0004 || !hatB().stageBoarding(body, this)) {
                fail(Refusal.GEOMETRY, "rim_arrival_not_supported"); return;
            }
            rimBoarded = true; mounted = true;
            body.family().crew().rememberFrame(hatB());
            GameplayLog.event(body, "aquatic", "transfer rim reached hat={} actual={} landing={} finalSlot={} remaining=deck_walk",
                    hatB().getUUID(), body.position(), landing, slot);
        }
        // The body faces along its trip.
        if (mode == Mode.LIFT && carrier != null) {
            float toCarrier = SurfaceApproach.yaw(body.position(), carrier.position());
            SurfaceApproach.face(body, toCarrier);
        } else if (geometry != null) {
            if (mode == Mode.HOP && !current.airborne(now)) {
                if (body.position().subtract(before).horizontalDistanceSqr() > 1e-8)
                    SurfaceApproach.face(body, SurfaceApproach.yaw(before, body.position()));
            } else SurfaceApproach.face(body, SurfaceApproach.yaw(geometry.start(), geometry.end()));
        }
        bodyYaw = body.yBodyRot;
        if (mode == Mode.LIFT && carrier != null) {
            SurfaceApproach.face(carrier, stand.yaw());
        }
        if (reverting) {
            if (u <= 1e-6) endRevert(body);
            return;
        }
        if (u >= 1 - 1e-9) {
            if (!commitArrival(body)) return;
            next(Phase.SETTLE);
        }
    }

    /** Makes the hull whose frame carries the body at this progress its vehicle (or none); false when the mount fails. */
    private boolean syncVehicle(YukkuriEntity body, double u, int want) {
        var current = body.getVehicle();
        int have = current instanceof HatBoatEntity h ? h.getId() : -1;
        if (have == want) { mounted = want >= 0; return true; }
        if (current != null) {
            YukkuriLocomotion.detach(body);
        }
        if (want < 0) { mounted = false; return true; }
        var hat = hat(level, want);
        if (hat == null || !body.startRiding(hat, true)) {
            fail(Refusal.GEOMETRY, "mount_refused");
            return false;
        }
        mounted = true;
        // The seat claim was consumed by the mount; the transfer's own curve still places the body.
        return true;
    }

    // ---------------------------------------------------------------- SETTLE / RELEASE
    /** Commit while MOVE still owns rider positioning, so the next rider tick cannot restore the old seat. */
    private boolean commitArrival(YukkuriEntity body) {
        if (arrived) return true;
        var destination = to.kind() == Kind.BERTH ? hatB() : null;
        if (to.kind() == Kind.BERTH) {
            var placed = destination == null ? null : destination.deck().get(bodyId);
            if (placed == null || slot < 0 || placed.target() != slot
                    || body.position().distanceToSqr(destination.toWorld(destination.deckLayout().slot(slot).pos())) > .0004) {
                fail(Refusal.TRANSIENT, "arrival_claim_or_contact_lost");
                return false;
            }
        }
        if (!syncVehicle(body, 1, to.kind() == Kind.BERTH ? boatB : -1)) return false;
        if (to.kind() == Kind.BERTH) {
            var b = hatB();
            var placed = b.deck().get(bodyId);
            if (placed != null && placed.target() == slot && !b.deck().arrived(bodyId, slot)) {
                fail(Refusal.TRANSIENT, "arrival_commit_refused");
                return false;
            }
            placed = b.deck().get(bodyId);
            if (placed == null || !placed.aboard() || placed.slot() != slot || placed.target() >= 0
                    || body.position().distanceToSqr(b.toWorld(b.deckLayout().slot(slot).pos())) > .0004) {
                finish(false, Refusal.TRANSIENT, "arrival_commit_lost");
                return false;
            }
            b.deck().lift(bodyId, false);
            b.syncSeats();
            body.family().crew().rememberFrame(b);
        } else if (to.kind() == Kind.WATER) {
            body.waterDistress().plunge(hatA() == null ? null : hatA().getUUID(), null, WaterIncidents.Cause.ofWhy(why));
        }
        arrived = true;
        return true;
    }
    private void settle(YukkuriEntity body, YukkuriEntity carrier, long now) {
        long age = now - phaseStart;
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0;
        if (!commitArrival(body)) return;
        holdsMove = false;
        if (mode == Mode.LIFT && carrier != null) {
            SurfaceApproach.face(carrier, stand.yaw());
            updateLiftContact(body, carrier, 1);
            contact = stand != null && stand.mouth() ? Contact.MOUTH_BITE : Contact.BRAID_CRADLE;
        }
        if (age >= settleTicks()) next(Phase.RELEASE);
    }

    private int settleTicks() {
        return switch (mode) {
            case LIFT -> {
                var clip = CrewClips.of(CrewClips.BRAID_CRADLE_LIFT);
                yield stand != null && stand.mouth() ? 6 : Math.max(2, clip.ticks("release", 46) - clip.ticks("place", 38));
            }
            case CLIMB -> {
                var clip = CrewClips.of(CrewClips.WATER_HAT_CLIMB);
                yield Math.max(2, clip.length() - clip.ticks("pull_end", 31));
            }
            case HOP -> 4;
        };
    }

    private void release(YukkuriEntity body, YukkuriEntity carrier, long now) {
        long age = now - phaseStart;
        contact = Contact.NONE;
        contactAt = null;
        bodyHeld = false;
        if (age >= 2) finish(true, null, "");
    }

    // ================================================================= contact points
    /** Non-lift movement shares the contact frame without re-evaluating its planned curve for presentation. */
    private void sampleBody(YukkuriEntity body) {
        var hat = contactFrame == 1 ? hatA() : contactFrame == 2 ? hatB() : null;
        var feet = hat == null ? body.position() : hat.toLocal(body.position());
        bodyPrev = bodyAt == null ? feet : bodyAt;
        bodyAt = feet;
        bodyYaw = body.yBodyRot;
    }

    private void setContact(HatBoatEntity frameHat, Vec3 local, int frame) {
        contactPrev = contactAt == null ? local : contactAt;
        contactAt = local;
        contactFrame = frame;
    }

    /** Contact and feet are one sample pair in the body's frame, including the handoff out of a hull. */
    private void updateLiftContact(YukkuriEntity body, YukkuriEntity carrier, double u) {
        var anchor = stand != null && stand.mouth() ? CrewReach.mouthAt(carrier, carrier.position(), carrier.yBodyRot) : CrewReach.root(carrier);
        var target = CrewReach.contactOn(body, body.position(), anchor);
        var hat = body.getVehicle() instanceof HatBoatEntity h ? h : null;
        int frame = hat == null ? 0 : hat.getId() == boatA ? 1 : hat.getId() == boatB ? 2 : 0;
        var local = frame == 0 ? target : hat.toLocal(target);
        var feet = frame == 0 ? body.position() : hat.toLocal(body.position());
        // Keep the preceding sample when mounting/dismounting, expressed in the new frame.
        // Resetting it to the new position loses a tick of interpolation exactly at the rim.
        var previousContact = contactWorld(contactAt);
        var previousFeet = contactWorld(bodyAt);
        contactPrev = contactAt == null ? local : contactFrame == frame ? contactAt
                : frame == 0 ? previousContact : hat.toLocal(previousContact);
        bodyPrev = bodyAt == null ? feet : contactFrame == frame ? bodyAt
                : frame == 0 ? previousFeet : hat.toLocal(previousFeet);
        contactAt = local;
        bodyAt = feet;
        contactFrame = frame;
    }

    private Vec3 contactWorld(Vec3 point) {
        if (point == null || contactFrame == 0) return point;
        var hat = contactFrame == 1 ? hatA() : hatB();
        return hat.toWorld(point);
    }

    private boolean reachesNow(YukkuriEntity body, YukkuriEntity carrier, double u) {
        var anchor = stand != null && stand.mouth() ? CrewReach.mouthAt(carrier, carrier.position(), carrier.yBodyRot) : CrewReach.root(carrier);
        var contactPoint = CrewReach.contactOn(body, body.position(), anchor);
        return stand != null && stand.mouth() ? CrewReach.mouthReaches(carrier, anchor, contactPoint) : CrewReach.braidReaches(carrier, anchor, contactPoint);
    }

    // ================================================================= failure and the end
    /** A slip: counted per event; the budget spent turns it into an incapable refusal. */
    private void slip(String reason) {
        var body = body();
        boolean more = body != null && body.family().crew().slip(level.getGameTime());
        fail(more ? Refusal.SLIP : Refusal.INCAPABLE, more ? reason : "slip_budget_spent:" + reason);
    }

    private void fail(Refusal refusal, String reason) {
        if (finished) return;
        var body = body();
        // The body is already where it was going; a late clock only ends the clip's tail.
        if ((phase == Phase.SETTLE || phase == Phase.RELEASE) && (reason.startsWith("phase_timeout") || reason.startsWith("stalled"))) {
            finish(true, null, "");
            return;
        }
        // Inside a hull's frame the curve is run backwards to its start (no teleport); anywhere else the body is let go where it is.
        // A cradle has no seat transform to return to, so it never reverses into one.
        if (phase == Phase.MOVE && !reverting && body != null && body.getVehicle() instanceof HatBoatEntity hull
                && hull.usesDeck() && entry != null) {
            double u = entry.progress(level.getGameTime());
            if (u > 1e-6) {
                reverting = true;
                pendingRefusal = refusal;
                pendingReason = reason;
                revertAt = level.getGameTime();
                revertFrom = u;
                publish();
                return;
            }
        }
        finish(false, refusal, reason);
    }

    private void endRevert(YukkuriEntity body) {
        finish(false, pendingRefusal == null ? Refusal.TRANSIENT : pendingRefusal, pendingReason);
    }

    /** Ordinary seat positioning may resume only if the body really returned to its source seat. */
    private boolean atOriginalSeat(YukkuriEntity body, HatBoatEntity hat) {
        if (from.kind() != Kind.BERTH || hat.getId() != boatA || !hat.usesDeck()) return false;
        var placed = hat.deck().get(bodyId);
        return placed != null && placed.slot() >= 0
                && body.position().distanceToSqr(hat.toWorld(hat.deckLayout().slot(placed.slot()).pos())) <= .0004;
    }

    private void finish(boolean success, Refusal refusal, String reason) {
        if (finished) return;
        finished = true;
        holdsMove = false;
        bodyHeld = false;
        var body = body();
        var carrier = carrier();
        long now = level.getGameTime();
        var outcome = new CrewPort.Outcome(success, refusal, reason, mode, now);
        if (mode == Mode.LIFT && body != null) BraidCarry.finished(body, carrier, this, outcome);
        var a = hatA();
        var b = hatB();
        if (body != null) {
            if(!success&&refusal==Refusal.GEOMETRY&&geometryKey!=null)body.family().crew().rememberImpossible(geometryKey,now);
            // A failed/reversed curve must not fall back to a stale seat transform and snap across its obstruction.
            if (!success && phase == Phase.MOVE && body.getVehicle() instanceof HatBoatEntity hat && !atOriginalSeat(body, hat)
                    && !hat.preserveSupportedPassenger(body, reason)) {
                GameplayLog.event(body, "aquatic", "transfer lost support hat={} actual={} reason={}", hat.getUUID(), body.position(), reason);
                YukkuriLocomotion.detach(body);
            }
            releaseClaim(body, success);
            body.fallDistance = 0;
            body.family().crew().drop(this, outcome);
            if (success) body.family().crew().settled(mode == Mode.LIFT);
        }
        if (carrier != null) {
            carrier.family().crew().drop(this, outcome);
            var ch = carrierHat(carrier);
            if (ch != null && carrier.getVehicle() == ch) {
                if (success && carrierSlot >= 0 && ch.deck().aboard(carrier.getUUID()) && !ch.deck().moving(carrier.getUUID()))
                    ch.deck().moveWithin(ch.bodyOf(carrier), carrierSlot, ch.toLocal(carrier.position()), false, Purpose.SERVICE);
                else {
                    var lease = ch.deck().lease(carrierId);
                    if (lease != null && lease.id() == carrierLease) ch.deck().release(carrierId, "transfer_done");
                }
                ch.releaseRescueOwner(carrier);
                ch.syncSeats();
            }
        }
        if (a != null) a.crew().detach(id);
        if (b != null) b.crew().detach(id);
        var ch = carrierHat(carrier);
        if (ch != null) ch.crew().detach(id);
        entry = null;
        if (!success && rescue && body != null) WaterIncidents.transferFailed(carrier, body, refusal, reason);
        if (success && body != null) {
            if (b != null) body.waterDistress().clearRescueHat(b);
            if (rescue && carrier != null) {
                body.life().personality().relationshipChanged(carrier.getUUID(), 10, -8);
                carrier.aquaticLife().care().expectNuzzle(body);
            }
        }
        var logAt = body != null ? body : carrier;
        if (logAt != null) GameplayLog.event(logAt, "aquatic",
                "transfer {} mode={}{} why={} reason={} class={} from={} to={} slot={} contact={} body={} carrier={} load={}->{} ticks={}",
                success ? "done" : "failed", mode, degraded ? "(mouth)" : "", why, success ? "ok" : reason, refusal == null ? "-" : refusal,
                from.key(), to.key(), slot < 0 ? "-" : (b == null ? "-" : b.deckLayout().slot(slot).id()), contact, bodyId,
                carrierId, fmt(loadBefore), fmt(loadAfter), ticksTotal);
        if (a != null) a.syncSeats();
        if (b != null && b != a) b.syncSeats();
    }

    /** No participant's goal has advanced the ticket for a long while. */
    public boolean stale(long now) { return !finished && now - lastTick > 40; }

    /** External end (death, unload, the decision layer withdrawing the request). */
    public void cancel(String reason) { if (!finished) fail(Refusal.TRANSIENT, reason); }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    // ================================================================= the synced entry
    private void publish() { entry = buildEntry(); }

    private CrewState.Entry buildEntry() {
        var g = geometry;
        var body = body();
        var carrier = carrier();
        if (g == null) g = new Geometry(false, List.of(new CrewState.Knot(0, 0, 0, 0)), List.of(0.0), 1, "", "", Vec3.ZERO, Vec3.ZERO, 0);
        return new CrewState.Entry(id, mode, phase, body == null ? -1 : body.getId(), bodyId, carrier == null ? -1 : carrier.getId(), carrierId,
                boatA, boatB, g.arc(), g.knots(), g.cumulative(), g.curve(), g.entryId(), slot, phaseStart, moveStart, g.moveTicks(), revertAt, revertFrom,
                contact, contactFrame, contactAt, contactPrev, bodyAt, bodyPrev, bodyYaw, prevBodyYaw, degraded);
    }
}
