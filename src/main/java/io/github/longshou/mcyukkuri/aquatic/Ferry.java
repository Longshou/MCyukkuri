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
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Walk;
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

    /** Close the braid's reach on the faller (or wait for it to come ashore by itself) before the shared lift. */
    static ArrayList<Supplier<Step>> chase(YukkuriEntity carrier, YukkuriEntity child) {
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> AquaticLife.ashore(child) || BraidFetch.withinReach(carrier, child) ? null
                : act(new Paddle(() -> child.isAlive() ? child.position() : null,
                distance -> child.isPassenger() || AquaticLife.ashore(child) || BraidFetch.withinReach(carrier, child))));
        return steps;
    }

    /** Load one young from the bank: board the brim place its braid reaches it from and lift it into a seat; when no brim place
     *  does, do not board first but take it from the ground and carry it aboard. No step is started on a stand its own admission
     *  refuses, and no failed step is run again. */
    static List<Supplier<Step>> loadFromBank(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child, Beckoning beckoning) {
        if (AquaticLife.aboard(carrier, hat))
            return BraidFetch.withinReach(carrier, child) ? BraidFetch.steps(carrier, child, hat)
                    : List.<Supplier<Step>>of(() -> boardHop(carrier, hat, child, beckoning));
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(approach(carrier, child));
        // A brim place her braid cannot reach it from is no boarding: it is taken from the ground and carried aboard instead.
        if (reachingStops(carrier, hat, child).isEmpty()) steps.addAll(BraidFetch.steps(carrier, child, hat));
        else steps.addAll(boardNear(carrier, hat, child, beckoning));
        return steps;
    }

    /** Walk up to the young on the bank (nothing when the braid already reaches it). */
    private static Supplier<Step> approach(YukkuriEntity carrier, YukkuriEntity child) {
        return () -> {
            if (BraidFetch.withinReach(carrier, child)) return null;
            var stand = SurfaceApproach.beside(carrier, child, (carrier.getBbWidth() + child.getBbWidth()) / 2 + .15, 1.2);
            return stand == null ? null : act(Walk.around(stand, child));
        };
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
            if (!new SeatTarget(hat, stop).available(carrier)) continue;
            if (ContactGeometry.braidGap(carrier, frame.toWorld(stop), child) <= 0) out.add(stop);
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
        return List.of(() -> new Step.Wait(hat::steady, 60, "hat_settles"), () -> {
                    if (hop.get().check(body).ok()) return null;
                    var stand = boardingStand(body, hat, beckoning);
                    if (stand != null) return SurfaceApproach.arrived(body, stand) ? null : act(new Walk(stand));
                    GameplayLog.limited(body, "aquatic", "board.no_stand", 200,
                            "board waiting reason=no_dry_hop_stand hat={}", hat.getUUID());
                    return new Step.Wait(() -> hat.settled(body), ClipMarkers.of(Behaviors.BOAT_CALL).length(), Behaviors.BOAT_CALL);
                },
                () -> act(new Face(SurfaceApproach.yaw(body.position(), hat.position()))),
                () -> beckoning == null || hop.get().check(body).reason() != Reason.TOO_FAR ? null : beckoning.step(body, hat),
                () -> {
                    var action = hop.get();
                    return action.blockedByBody(body) ? bodyWait(body, action) : null;
                },
                () -> act(hop.get()));
    }

    /** A dry walking destination admitted by the boarding arc, or by the owner's one braid stroke to draw her hat closer. */
    private static Vec3 boardingStand(YukkuriEntity body, PlacedHatEntity hat, Beckoning beckoning) {
        for (var edge : Waters.edges(body, hat.position(), 4, false)) {
            var stand = Waters.boardingStand(edge, body);
            if (SurfaceApproach.supported(body, stand)
                    && (new Hop(hat).checkFrom(body, stand).ok()
                        || beckoning != null && beckoning.nearFrom(body, hat, stand, edge.yaw()))
                    && SurfaceApproach.canApproach(body, stand)) return stand;
        }
        return null;
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
        var seat = hat.restSpot(body);
        if (seat != null && !(AquaticLife.aboard(body, hat) && Waters.standsAt(hat, body, seat.local()))) {
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
                var exit = Waters.brimAshore(hat, body);
                return exit == null ? null : act(Hop.off(hat, exit.brim(), exit.bank()));
            }
            var at = Waters.ashore(body);
            return at == null ? null : act(new Hop(at));
        };
    }

}
