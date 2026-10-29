package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * The short runs of shared actions the aquatic intents put a body through to get itself, or a young it carries, on and off a hat
 * (refactor WP6a; spec 9.4 Grab, Walk, Hop, Release; the young is carried in the mouth as {@code Fetch} carries it): every target
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

    /** Aboard: walk on the hat to just short of the young, face it, take it in the mouth. */
    static List<Supplier<Step>> grabAboard(YukkuriEntity carrier, PlacedHatEntity hat, YukkuriEntity child) {
        return List.of(() -> {
                    if (new Grab(child).check(carrier).ok()) return null;
                    var frame = hat.frame();
                    var mine = frame.toLocal(carrier.position()); var theirs = frame.toLocal(child.position());
                    var away = mine.subtract(theirs).multiply(1, 0, 1);
                    double gap = (Occupancy.foot(carrier.getBbWidth()) + Occupancy.foot(child.getBbWidth())) / hat.unit() + .5;
                    var to = away.lengthSqr() < 1e-6 ? theirs : theirs.add(away.normalize().scale(gap));
                    var height = hat.support(to, Occupancy.foot(carrier.getBbWidth()));
                    return height.isEmpty() ? null : act(new Walk(hat, new Vec3(to.x, height.getAsDouble(), to.z)));
                },
                () -> act(new Face(SurfaceApproach.yaw(carrier.position(), child.position()))),
                () -> act(new Grab(child)));
    }

    /** Onto the hat's hollow by a hop from here, walking first to the bank edge nearest it when no landing is in reach now. */
    static List<Supplier<Step>> hopAboard(YukkuriEntity body, PlacedHatEntity hat) {
        return List.of(() -> {
                    if (Hop.onto(body, hat) != null) return null;
                    var edge = Waters.beside(hat, body);
                    return edge == null || SurfaceApproach.arrived(body, edge.stand(body.getBbWidth())) ? null : act(Walk.stand(edge.stand(body.getBbWidth()), 1));
                },
                () -> Hop.onto(body, hat) != null ? null : act(new Face(SurfaceApproach.yaw(body.position(), hat.position()))),
                // No landing even now: the hop at the hat's middle is refused with its real shortfall (TOO_FAR, TOO_HIGH).
                () -> { var at = Hop.onto(body, hat); return act(new Hop(at == null ? hat.position() : at)); });
    }

    /** Off the hat onto the nearest dry stand a hop from here lands on; none is no way on. */
    static Supplier<Step> hopAshore(YukkuriEntity body) {
        return () -> { var at = Waters.ashore(body); return at == null ? null : act(new Hop(at)); };
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
