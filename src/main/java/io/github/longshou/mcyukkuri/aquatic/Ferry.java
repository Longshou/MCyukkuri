package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Walk;
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

    /** A continuous chase from the captain seat, then the shared braid pickup and set-down. */
    static List<Supplier<Step>> fetchAboard(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> ContactGeometry.inBraidReach(carrier, child) ? null : act(new Paddle(child::position,
                distance -> !child.isAlive() || child.isPassenger() || ContactGeometry.inBraidReach(carrier, child))));
        steps.addAll(BraidFetch.steps(carrier, child, hat));
        return steps;
    }

    /** The two seats are within the shared pickup's reach; turning does not move either rider. */
    static List<Supplier<Step>> grabAboard(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        return List.of(() -> act(new Face(SurfaceApproach.yaw(carrier.position(), child.position()))),
                () -> act(new Grab(child)));
    }

    /** Approach the bank, then land at the host's current admitted seat. */
    static List<Supplier<Step>> hopAboard(YukkuriEntity body, PlacedHatEntity hat, Beckoning beckoning) {
        if (AquaticLife.aboard(body, hat)) return null;
        return List.of(() -> new Step.Wait(hat::steady, 60, "hat_settles"), () -> {
                    if (new Hop(hat).check(body).ok()) return null;
                    var edge = Waters.beside(hat, body);
                    var stand = edge == null ? null : Waters.boardingStand(edge, hat, body);
                    return stand == null || SurfaceApproach.arrived(body, stand) ? null : act(new Walk(stand));
                },
                () -> act(new Face(SurfaceApproach.yaw(body.position(), hat.position()))),
                () -> beckoning == null || new Hop(hat).check(body).reason() != Reason.TOO_FAR ? null : beckoning.step(body, hat),
                () -> act(new Hop(hat)));
    }

    /** One intent run's beckoning: its own hat afloat within a braid stroke of the bank where it stands is stroked in (Beckon), and
     *  once one such try failed (too far still, out of energy, nothing to draw) the run does not beckon again but waits for drift
     *  or takes another way: no strokes paid over and over. */
    static final class Beckoning {
        private Beckon tried;

        /** The beckon step, or null when it does not apply or this run's last one failed. */
        Step step(YukkuriEntity body, PlacedHatEntity hat) {
            if (failed() || PlacedHatEntity.find(body) != hat || !AquaticLife.afloat(hat) || !AquaticLife.ashore(body)
                    || hat.position().subtract(body.position()).horizontalDistance() > Beckon.REACH) return null;
            return act(tried = new Beckon(hat));
        }

        boolean failed() { return tried != null && !tried.drawn(); }
    }

    /** Hop from the actual seat to a dry stand in the body's reach. */
    static Supplier<Step> hopAshore(YukkuriEntity body) {
        return () -> {
            var at = Waters.ashore(body);
            return at == null ? null : act(new Hop(at));
        };
    }

}
