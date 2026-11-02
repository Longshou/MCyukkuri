package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * The short runs of shared actions for boarding, leaving and fetching a young from the captain seat: every target
 * is read when its step is due. A step that cannot be done ends blocked and the intent's runner counts it; nothing here retries.
 */
final class Ferry {
    private Ferry() {}

    private static Step act(Action action) { return new Step.Do(action); }

    /** From pickup reach, carry to a reachable bank, using the shared brim route if the seat cannot clear it directly. */
    static List<Supplier<Step>> fetchAshore(YukkuriEntity carrier, YukkuriEntity child, Release release) {
        var hat = AquaticLife.hat(carrier);
        var bank = Waters.ashore(carrier);
        var brim = bank == null ? Waters.brimAshore(hat, carrier) : null;
        var stand = brim == null ? bank : Waters.ashore(carrier, hat.toWorld(brim));
        if (stand == null) return null;
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> act(new Grab(child)));
        if (brim != null) steps.add(() -> act(new Hop(hat, brim)));
        steps.add(() -> act(new Hop(stand)));
        steps.add(() -> act(release));
        return steps;
    }

    /** Close the braid's reach on the faller (or wait for it to come ashore by itself) before the shared lift. */
    static ArrayList<Supplier<Step>> chase(YukkuriEntity carrier, YukkuriEntity child) {
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> AquaticLife.ashore(child) || BraidFetch.withinReach(carrier, child) ? null : act(new Paddle(child::position,
                distance -> !child.isAlive() || child.isPassenger() || AquaticLife.ashore(child) || BraidFetch.withinReach(carrier, child))));
        return steps;
    }

    /** Load from the near brim; a wandering child is approached again on foot after a real hop ashore. */
    static List<Supplier<Step>> loadFromBank(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child, Beckoning beckoning) {
        if (AquaticLife.aboard(carrier, hat))
            return BraidFetch.withinReach(carrier, child) ? BraidFetch.steps(carrier, child, hat) : List.of(hopAshore(carrier));
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> {
            if (BraidFetch.withinReach(carrier, child)) return null;
            var stand = SurfaceApproach.beside(carrier, child, (carrier.getBbWidth() + child.getBbWidth()) / 2 + .15, 1.2);
            return stand == null ? null : act(Walk.around(stand, child));
        });
        steps.addAll(hopAboard(carrier, hat, beckoning, true));
        return steps;
    }

    /** The two seats are within the shared pickup's reach; turning does not move either rider. */
    static List<Supplier<Step>> grabAboard(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        return List.of(() -> act(new Face(SurfaceApproach.yaw(carrier.position(), child.position()))),
                () -> act(new Grab(child)));
    }

    /** Approach the bank, then land at the host's current admitted seat. A body standing in the takeoff arc — one not riding the
     *  hull — is waited out with the boat call; a body entering after takeoff remains an honest flight failure. */
    static List<Supplier<Step>> hopAboard(YukkuriEntity body, PlacedHatEntity hat, Beckoning beckoning) {
        return hopAboard(body, hat, beckoning, false);
    }

    private static List<Supplier<Step>> hopAboard(YukkuriEntity body, PlacedHatEntity hat, Beckoning beckoning, boolean brim) {
        if (AquaticLife.aboard(body, hat)) return brim || hat.seated(body) ? null : List.of(() -> act(new Hop(hat)));
        Supplier<Hop> hop = () -> brim ? Hop.ontoBrim(hat) : new Hop(hat);
        return List.of(() -> new Step.Wait(hat::steady, 60, "hat_settles"), () -> {
                    if (hop.get().check(body).ok()) return null;
                    var edge = Waters.beside(hat, body);
                    var stand = edge == null ? null : Waters.boardingStand(edge, body);
                    return stand == null || SurfaceApproach.arrived(body, stand) ? null : act(new Walk(stand));
                },
                () -> act(new Face(SurfaceApproach.yaw(body.position(), hat.position()))),
                () -> beckoning == null || hop.get().check(body).reason() != Reason.TOO_FAR ? null : beckoning.step(body, hat),
                () -> {
                    var action = hop.get();
                    return action.blockedByBody(body) ? bodyWait(body, action) : null;
                },
                () -> act(hop.get()));
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
            return tried == null && PlacedHatEntity.find(body) == hat && AquaticLife.onWater(hat) && AquaticLife.ashore(body)
                    && new Beckon(hat).check(body).ok();
        }

        /** The beckon step, or null when no stroke applies; one try per run, so a drawn-in or failed stroke is never paid over again. */
        Step step(YukkuriEntity body, PlacedHatEntity hat) { return near(body, hat) ? act(tried = new Beckon(hat)) : null; }
    }

    /** Hop from the actual seat to a dry stand in the body's reach. A small body may first hop to a brim spot from which that
     *  same stand is reachable; the next derivation performs the ordinary hop off the host. */
    static Supplier<Step> hopAshore(YukkuriEntity body) {
        return () -> {
            var at = Waters.ashore(body);
            if (at != null) return act(new Hop(at));
            if (!(body.getVehicle() instanceof PlacedHatEntity hat)) return null;
            var local = Waters.brimAshore(hat, body);
            return local == null ? null : act(new Hop(hat, local));
        };
    }

}
