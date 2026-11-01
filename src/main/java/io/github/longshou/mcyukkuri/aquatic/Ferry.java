package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
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
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import java.util.List;
import java.util.function.Supplier;

/**
 * The short runs of shared actions the aquatic intents put a body through to get itself, or a young it carries, on and off a hat
 * (Grab, Walk, Hop, Release; the young is carried in the mouth as {@code Fetch} carries it): every target
 * is read when its step is due. A step that cannot be done ends blocked and the intent's runner counts it; nothing here retries.
 */
final class Ferry {
    /** The family pickup's walking pace (Fetch's). */
    private static final double PACE = 1.1;

    private Ferry() {}

    private static Step act(Action action) { return new Step.Do(action); }

    /** Ashore: walk to the stand beside the young the family pickup uses, face it, take it in the mouth. */
    static List<Supplier<Step>> grabAshore(YukkuriEntity carrier, YukkuriEntity child) {
        return List.of(() -> {
                    if (new Grab(child).check(carrier).ok()) return null;
                    var stand = SurfaceApproach.beside(carrier, child, (carrier.getBbWidth() + child.getBbWidth()) * .5 + .15, 1.2);
                    return stand == null ? null : act(Walk.stand(stand, PACE));
                },
                () -> act(new Face(SurfaceApproach.yaw(carrier.position(), child.position()))),
                () -> act(new Grab(child)));
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

    /** Aboard with the young held: turn the hold (the mouth, or the braid side) to the hollow's middle, let the hat settle, put it
     *  down where it hangs (it drops in). */
    static List<Supplier<Step>> releaseAboard(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        return List.of(() -> act(new Face(ParentCarry.hold(carrier, child).reach() == ContactGeometry.Reach.BRAID
                        ? ContactGeometry.braidYaw(carrier, hat) : SurfaceApproach.yaw(carrier.position(), hat.position()))),
                () -> hat.steady() ? null : new Step.Wait(hat::steady, 60, "hat_settles"),
                () -> act(new Release(child)), landed(child));
    }

    /** After a let-go: until the released body has come down somewhere (a hat, the ground, the water), so the next step reads
     *  where it is rather than where it falls through. */
    static Supplier<Step> landed(YukkuriEntity child) {
        return () -> new Step.Wait(() -> !child.isAlive() || child.isPassenger() || child.onGround() || child.isInWaterOrBubble(), 40, "landing");
    }
}
