package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.geom.BankStand;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The short runs of shared actions for boarding, leaving and fetching a young from the captain seat: every target
 * is read when its step is due. A step that cannot be done ends blocked and the intent's runner counts it; nothing here retries.
 */
final class Ferry {
    private Ferry() {}

    private static Step act(Action action) { return new Step.Do(action); }

    /** One approach and up to two corrections before the shared lift. */
    static List<Supplier<Step>> rescuePickup(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        return List.of(new Plan.Approach<>(3, () -> ContactGeometry.braidGrip(carrier, child).check(),
                        reason -> rescueMove(carrier, hat, child, reason), Ferry::act),
                () -> act(new Grab(child, hat)));
    }

    /** Stop rowing when the grip is ready or its next correction needs different footing. */
    private static boolean alongside(YukkuriEntity carrier, YukkuriEntity child) {
        if (child.isPassenger() || AquaticLife.ashore(child)) return true;
        var grip = ContactGeometry.braidGrip(carrier, child);
        return grip.ready() || !grip.supported();
    }

    private static Action rescueMove(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child, Reason reason) {
        if (!child.isAlive() || child.isPassenger() || AquaticLife.ashore(child)) return null;
        if (reason == Reason.NO_CONTACT)
            return new Paddle(() -> child.isAlive() ? child.position() : null, distance -> alongside(carrier, child));

        var from = hat.riders().local(carrier.getUUID());
        var rest = hat.restSpot(carrier);
        var stops = new ArrayList<>(hat.hopStops(carrier));
        var entry = hat.restEntry(carrier);
        if (entry != null) stops.add(entry.local());
        var frame = hat.frame();
        stops.sort(Comparator.comparingDouble(stop -> frame.toWorld(stop).distanceToSqr(child.position())));
        if (rest != null) stops.addFirst(rest.local());
        var search = Hop.Search.admission();
        Hop move = null;
        Vec3 destination = null;
        for (var stop : stops) {
            if (Waters.standsAt(hat, carrier, stop)) continue;
            // Inside the cabin the seating move crosses the floor before turning. A direct jump
            // would need a supported takeoff turn at the very spot that refused the grip.
            boolean reseat = rest != null && stop.equals(rest.local()) && hat.insideFloor(from);
            float yaw = reseat ? hat.restFacing(carrier) - hat.getYRot()
                    : Hop.arrivalYaw(carrier, carrier.position(), new SeatTarget(hat, stop));
            var grip = ContactGeometry.braidGrip(carrier, hat, stop, yaw, child);
            if (!grip.supported() || !grip.ready() && move != null) continue;
            var hop = reseat ? new Hop(hat) : new Hop(hat, stop);
            if (!hop.check(carrier, search).ok()) continue;
            destination = stop;
            move = hop;
            if (grip.ready()) break;
        }
        if (move != null) GameplayLog.event(carrier, "aquatic", "rescue change stance faller={} reason=unstable_grip_stand from={} to={}",
                child.getUUID(), from, destination);
        return move;
    }

    /** Load one young from the bank: board the brim place its braid reaches it from and lift it into a seat; when no brim place
     *  does, do not board first but take it from the ground and carry it aboard; when she has no way aboard now (the hull out
     *  of reach, her place taken) she works on getting aboard herself first and lifts nothing. No step is started on a stand
     *  its own admission refuses, and no failed step is run again. */
    static List<Supplier<Step>> loadFromBank(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child, Beckoning beckoning) {
        if (AquaticLife.aboard(carrier, hat))
            return BraidFetch.withinReach(carrier, child) ? BraidFetch.steps(carrier, child, hat)
                    : List.<Supplier<Step>>of(() -> boardHop(carrier, hat, child, beckoning));
        if (!reachingStops(carrier, hat, child).isEmpty()) return boardNear(carrier, hat, child, beckoning);
        // She does not lift a young she could only set down again: with no way aboard now (the hull out of reach, her place taken)
        // she works on getting aboard herself first.
        if (!canBoard(carrier, hat)) return hopAboard(carrier, hat, beckoning);
        var steps = new ArrayList<Supplier<Step>>();
        var grab = new Grab(child, hat);
        steps.add(new Plan.Approach<>(() -> grab.check(carrier), () -> grab.nextContactStand(carrier),
                stand -> act(Walk.around(stand, child))));
        steps.add(() -> act(grab));
        steps.addAll(hopAboard(carrier, hat, beckoning));
        steps.add(() -> act(new Release(child, hat)));
        return steps;
    }

    /** How many brim places a boarding is chosen from. */
    private static final int STOPS = 8;

    /** The brim places she could work a young from, nearest to it first: those whose braid reaches it once she stands there.
     *  Empty when no brim place does. */
    private static List<Vec3> reachingStops(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        var frame = hat.frame();
        int asked = 0;
        var out = new ArrayList<Vec3>();
        for (var stop : hat.hopStops(carrier).stream()
                .sorted(Comparator.comparingDouble(stop -> frame.toWorld(stop).distanceToSqr(child.position()))).toList()) {
            if (asked++ >= STOPS) break;
            if (AquaticLife.aboard(carrier, hat) && Waters.standsAt(hat, carrier, stop)) continue;
            var seat = new SeatTarget(hat, stop);
            float yaw = Hop.arrivalYaw(carrier, carrier.position(), seat);
            if (!seat.available(carrier, yaw)) continue;
            if (ContactGeometry.braidGrip(carrier, hat, stop, yaw, child).ready()) out.add(stop);
        }
        return out;
    }

    /** The two seats are within the shared pickup's reach; turning does not move either rider. */
    static List<Supplier<Step>> grabAboard(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        return List.of(() -> act(new Face(SurfaceApproach.yaw(carrier.position(), child.position()))),
                () -> act(new Grab(child)));
    }

    /** Approach the bank, then land at the host's current admitted seat. A body standing in the takeoff arc — one not riding the
     *  hull — is waited out with the boat call; a body entering after takeoff remains an honest flight failure. */
    static List<Supplier<Step>> hopAboard(YukkuriEntity body, PlacedHatEntity hat, Beckoning beckoning) {
        if (AquaticLife.aboard(body, hat)) return hat.settled(body) ? null : List.of(() -> act(new Hop(hat)));
        Supplier<Hop> hop = () -> new Hop(hat);
        return List.of(() -> {
                    for (var rider : body.getPassengers()) if (rider instanceof YukkuriEntity child)
                        GameplayLog.event(body, "aquatic", "boarding from bank child={} hat={}", child.getUUID(), hat.getUUID());
                    return new Step.Wait(hat::steady, 60, "hat_settles");
                },
                () -> approachHull(body, hat, beckoning),
                () -> act(new Face(SurfaceApproach.yaw(body.position(), hat.position()))),
                () -> beckoning == null || !drawnToBank(hop.get().check(body)) ? null : beckoning.step(body, hat),
                // after the turn the way aboard is read again from where she now stands (a lip leg, a stroke already paid)
                () -> approachHull(body, hat, null),
                () -> {
                    var action = hop.get();
                    return action.blockedByBody(body) ? bodyWait(body, action) : null;
                },
                () -> act(hop.get()));
    }

    /** A refused landing a hull nearer the bank can change: out of hop reach, or no room because its young cannot leave it from here
     *  either (their own exit is out of reach too). The braid stroke draws the hull in, once per run; another refusal is not helped. */
    private static boolean drawnToBank(Feasibility ready) {
        return ready.reason() == Reason.TOO_FAR || ready.reason() == Reason.NO_ROOM;
    }

    private static Step approachHull(YukkuriEntity body, PlacedHatEntity hat, Beckoning beckoning) {
        var hop = new Hop(hat);
        var search = Hop.Search.admission();
        var ready = hop.check(body, search);
        if (ready.ok()) return null;
        var move = boardingApproach(body, hat, beckoning, search);
        if (move != null) return act(move);
        GameplayLog.limited(body, "aquatic", "board.no_stand", 200,
                "board waiting reason=no_dry_hop_stand hat={} hop={} detail={} carrying={}", hat.getUUID(), ready.reason(), ready.detail(),
                body.getPassengers().size());
        return new Step.Wait(() -> hop.check(body, Hop.Search.admission()).ok(),
                ClipMarkers.of(Behaviors.BOAT_CALL).length(), Behaviors.BOAT_CALL);
    }

    /** Walk to an admitted takeoff when possible. A corner too narrow to walk onto can still support a hop landing. */
    private static Action boardingApproach(YukkuriEntity body, PlacedHatEntity hat, Beckoning beckoning, Hop.Search search) {
        var edges = Waters.edges(body, hat.position(), 4, false);
        var board = new Hop(hat);
        Vec3 strokeStand = null;
        var walkStands = new ArrayList<Vec3>();
        for (var edge : edges) {
            var centre = Waters.boardingStand(edge, body);
            var aligned = Floor.point(BankStand.alongFace(Floor.vec(centre), Floor.vec(edge.normal()), Floor.vec(hat.position())));
            for (var stand : aligned.equals(centre) ? List.of(centre) : List.of(aligned, centre)) {
                if (!SurfaceApproach.supported(body, stand)) continue;
                walkStands.add(stand);
                // A walk already within its arrival tolerance would leave the refused takeoff unchanged.
                boolean reachable = !SurfaceApproach.at(body, stand) && board.checkFrom(body, stand, search).ok();
                if (!reachable && (strokeStand != null || beckoning == null
                        || !beckoning.nearFrom(body, hat, stand, SurfaceApproach.yaw(stand, hat.position())))) continue;
                if (!SurfaceApproach.canApproach(body, stand)) continue;
                if (reachable) return new Walk(stand);
                strokeStand = stand;
            }
        }
        if (AquaticLife.ashore(body)) for (var edge : edges) {
            var lip = Hop.bankStand(edge.edge(), edge.normal(), hat.position());
            if (SurfaceApproach.at(body, lip)) continue;
            var toLip = new Hop(lip);
            if (!board.checkFrom(body, lip, search).ok()) continue;
            if (toLip.check(body, search).ok()) return toLip;
            // Too far from the bank corner here: walk to the stand beside it first, and hop there.
            for (var stand : walkStands)
                if (!SurfaceApproach.at(body, stand) && SurfaceApproach.canApproach(body, stand)
                        && toLip.checkFrom(body, stand, search).ok()) return new Walk(stand);
        }
        return strokeStand == null ? null : new Walk(strokeStand);
    }

    /** The same direct hop, bank walk or supported corner hop that boarding can actually use. */
    static boolean canBoard(YukkuriEntity body, PlacedHatEntity hat) {
        return Waters.boardable(body, hat, () -> {
            // One decision: the hull's place for this young is searched once, not again from every bank stand.
            var search = Hop.Search.admission();
            return new Hop(hat).check(body, search).ok() || boardingApproach(body, hat, new Beckoning(), search) != null;
        });
    }

    /** Board to work a young from the hull: walk to the boarding stand, face it, then hop where the braid reaches the young from —
     *  chosen where she stands, so its own hop admission is the one read. */
    static List<Supplier<Step>> boardNear(YukkuriEntity body, PlacedHatEntity hat, YukkuriEntity child, Beckoning beckoning) {
        var edge = Waters.beside(hat, body);
        var stand = edge == null ? null : Waters.boardingStand(edge, body);
        return List.of(() -> stand == null || SurfaceApproach.arrived(body, stand) ? null : act(new Walk(stand)),
                () -> {
                    float yaw = SurfaceApproach.yaw(body.position(), hat.position());
                    return Math.abs(Mth.wrapDegrees(yaw - body.yBodyRot)) > Face.FACE_ARRIVED ? act(new Face(yaw)) : null;
                },
                () -> boardHop(body, hat, child, beckoning));
    }

    /** One boarding hop: the nearest brim place the braid reaches the young from whose own hop is admitted here, else her own
     *  seat, else her entrance place. */
    private static Step boardHop(YukkuriEntity body, PlacedHatEntity hat, YukkuriEntity child, Beckoning beckoning) {
        for (var stop : reachingStops(body, hat, child)) {
            var hop = new Hop(hat, stop);
            if (hop.check(body).ok()) return act(hop);
        }
        if (AquaticLife.aboard(body, hat)) return hopAshore(body).get();
        var seat = hat.restSpot(body);
        if (seat != null) {
            var hop = new Hop(hat, seat.local());
            if (hop.check(body).ok()) return act(hop);
        }
        var entrance = Hop.ontoBrim(hat, child.position());
        var available = entrance.check(body);
        if (available.ok()) return act(entrance);
        if (beckoning != null && available.reason() == Reason.TOO_FAR) {
            var stroke = beckoning.step(body, hat);
            if (stroke != null) return stroke;
        }
        GameplayLog.limited(body, "aquatic", "board.no_place", 200, "board no place child={} reason={} detail={}",
                child.getUUID(), available.reason(), available.detail());
        return entrance.blockedByBody(body) ? bodyWait(body, entrance) : null;
    }

    /** Wait for a body in the takeoff arc, bounded by three boat calls; the shared hop check is read again every tick. */
    private static Step bodyWait(YukkuriEntity body, Hop hop) {
        int ticks = ClipMarkers.of(Behaviors.BOAT_CALL).length();
        GameplayLog.limited(body, "aquatic", "board.wait", ticks, "board waiting reason=body_in_arc budget={}", ticks * 3);
        return new Step.Wait(() -> !hop.blockedByBody(body), ticks * 3, Behaviors.BOAT_CALL);
    }

    /** One intent run's beckoning: its own hat afloat within a braid stroke of the bank where it stands is stroked in (Beckon), and
     *  once one such try failed (too far still, out of energy, nothing to draw) the run does not beckon again but waits for drift
     *  or takes another way: no strokes paid over and over. */
    static final class Beckoning {
        private Beckon tried;

        /** Whether one stroke could help now: none tried yet this run, her own hat on the water, and the stroke itself applicable
         *  where she stands facing it — the action's own check is the only reach test, so admitting a stroke and running it never
         *  disagree. The cheap read a wait can make every tick. */
        boolean near(YukkuriEntity body, PlacedHatEntity hat) {
            return nearFrom(body, hat, body.position(), body.getYRot());
        }

        private boolean nearFrom(YukkuriEntity body, PlacedHatEntity hat, Vec3 stand, float yaw) {
            return tried == null && PlacedHatEntity.find(body) == hat && AquaticLife.onWater(hat) && AquaticLife.ashore(body)
                    && new Beckon(hat).checkFrom(body, stand, yaw).ok();
        }

        /** The beckon step, or null when no stroke applies; one try per run, so a drawn-in or failed stroke is never paid over again. */
        Step step(YukkuriEntity body, PlacedHatEntity hat) { return near(body, hat) ? act(tried = new Beckon(hat)) : null; }
    }

    /** Leave through the admitted brim and bank points in one shared hop action. */
    static Supplier<Step> hopAshore(YukkuriEntity body) {
        return () -> {
            if (body.getVehicle() instanceof PlacedHatEntity hat) {
                var hop = Waters.shoreHop(hat, body);
                return hop == null ? null : act(hop);
            }
            var at = Waters.ashore(body);
            return at == null ? null : act(new Hop(at));
        };
    }

}
