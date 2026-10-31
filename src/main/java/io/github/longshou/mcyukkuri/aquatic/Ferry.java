package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

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

    /** Aboard: walk on the hat toward just short of the young (as near as the hull stays upright with her), face it, take it. */
    static List<Supplier<Step>> grabAboard(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        return List.of(() -> {
                    if (new Grab(child).check(carrier).ok()) return null;
                    var frame = hat.frame();
                    var mine = frame.toLocal(carrier.position()); var theirs = frame.toLocal(child.position());
                    var away = mine.subtract(theirs).multiply(1, 0, 1);
                    double gap = (Occupancy.foot(carrier.getBbWidth()) + Occupancy.foot(child.getBbWidth())) / hat.unit() + .5;
                    var to = AquaticLife.spot(hat, carrier, false, away.lengthSqr() < 1e-6 ? theirs : theirs.add(away.normalize().scale(gap)));
                    return to == null || to.subtract(mine).horizontalDistance() < .1 ? null : act(new Walk(hat, to));
                },
                () -> act(new Face(SurfaceApproach.yaw(carrier.position(), child.position()))),
                () -> act(new Grab(child)));
    }

    /** Onto the hat's hollow by a hop from here, walking first to the bank edge nearest it when no landing is in reach now; its own
     *  hat floating out of a hop from there is drawn in to the bank first, by the run's {@code beckoning} (null: never). */
    static List<Supplier<Step>> hopAboard(YukkuriEntity body, PlacedHatEntity hat, Beckoning beckoning) {
        if (AquaticLife.aboard(body, hat)) {
            var spot = AquaticLife.freeSpot(hat, body);
            return spot == null ? null : List.of(() -> act(new Walk(hat, spot)));
        }
        return List.of(() -> {
                    if (landing(body, hat) != null) return null;
                    var edge = Waters.beside(hat, body);
                    var stand = edge == null ? null : Waters.boardingStand(edge, hat, body);
                    return stand == null || SurfaceApproach.arrived(body, stand) ? null : act(new Walk(stand));
                },
                () -> landing(body, hat) != null ? null : act(new Face(SurfaceApproach.yaw(body.position(), hat.position()))),
                () -> beckoning == null || landing(body, hat) != null || new Hop(hat.position()).check(body).ok() ? null : beckoning.step(body, hat),
                () -> new Step.Wait(() -> hat.tickCount > 0 && hat.steady(), 60, "hat_settles"),
                // No landing even now: the hop at the hat's middle is refused with its real shortfall (TOO_FAR, TOO_HIGH).
                () -> {
                    var at = landing(body, hat);
                    GameplayLog.limited(body, "aquatic", "boarding_hop", 100, "boarding hop hat={} target={} hull={} velocity={} steady={} tilt={}/{}",
                            hat.getUUID(), GameplayLog.at(at), GameplayLog.at(hat.position()), hat.getDeltaMovement(), hat.steady(), hat.roll(), hat.pitch());
                    return act(new Hop(at == null ? hat.position() : at));
                },
                () -> {
                    var spot = AquaticLife.freeSpot(hat, body);
                    return spot == null ? null : act(new Walk(hat, spot));
                });
    }

    /** A short hop may reach only the brim; the same boarding run then walks into the hollow. */
    private static Vec3 landing(YukkuriEntity body, PlacedHatEntity hat) {
        var inside = Hop.onto(body, hat);
        if (inside != null) return inside;
        var spot = AquaticLife.spot(hat, body, false, hat.frame().toLocal(body.position()), false);
        var at = spot == null ? null : hat.frame().toWorld(spot);
        if (at == null) return null;
        var hop = new Hop(at).check(body);
        if (!hop.ok()) GameplayLog.limited(body, "aquatic", "boarding_landing", 100,
                "boarding landing refused hat={} target={} reason={}", hat.getUUID(), GameplayLog.at(at), hop);
        return hop.ok() ? at : null;
    }

    static boolean inHollow(YukkuriEntity body, PlacedHatEntity hat) {
        return AquaticLife.aboard(body, hat) && Occupancy.of(body, hat.container()) == Occupancy.INSIDE_SUPPORTED;
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

    /** Off the hat onto the nearest dry stand a hop from here lands on; with none, aboard by a bank, first a walk on the hat to its
     *  side nearest the bank (a step off is always allowed, heel or not; the next step hops from there); else no way on. */
    static Supplier<Step> hopAshore(YukkuriEntity body) {
        return () -> {
            var at = Waters.ashore(body);
            if (at != null) return act(new Hop(at));
            var hat = AquaticLife.riding(body);
            var edge = hat == null ? null : Waters.edges(body, body.position(), 2, false).stream().findFirst().orElse(null);
            var spot = edge == null ? null : AquaticLife.spot(hat, body, false, hat.frame().toLocal(edge.edge()), false);
            return spot == null || spot.subtract(hat.frame().toLocal(body.position())).horizontalDistance() < .1 ? null : act(new Walk(hat, spot));
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
