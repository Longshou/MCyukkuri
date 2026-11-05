package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;

/** A rider moves to its own cabin place after landing or a braid set-down. */
final class TakeSeat implements Intent {
    private final YukkuriEntity body;
    private final PlacedHatEntity hat;
    private final SeatTarget target;
    private final Action move;

    private TakeSeat(YukkuriEntity body, PlacedHatEntity hat, SeatTarget target, Action move) {
        this.body = body;
        this.hat = hat;
        this.target = target;
        this.move = move;
    }

    /** No offer without a real means; a wall rider is left to squeeze/settle, even while crowded. */
    static TakeSeat offer(YukkuriEntity body, PlacedHatEntity hat) {
        if (body.getVehicle() != hat || !Floor.standing(body)) return null;
        // A young on the brim that can exercise is playing there; fear or a gasp sends it in (PlayAboard), not a cabin place that merely
        // is free. One that cannot play there walks or hops into the free cabin place itself, or is lifted in (Care.needsShelter).
        if (PlayAboard.onBrim(body, hat) && body.childhood().canExercise()) return null;
        var at = hat.riders().local(body.getUUID());
        // Seated is seated: a young already standing on an admitted cabin place stays, however a nearer-the-captain place shifts.
        if (hat.settled(body) && new SeatTarget(hat, at, SeatTarget.Kind.CABIN).available(body)) return null;
        var ability = BodyAbility.of(body);
        if (ability.walkSpeed() <= 0 && ability.hopReach() <= 0) return null;
        var places = hat.places(body, SeatTarget.Kind.CABIN, 1);
        if (places.isEmpty()) return null;
        var target = places.getFirst();
        if (at.distanceTo(target.local()) * hat.unit() <= .02) return null;
        if (ability.walkSpeed() > 0 && !hat.walkRoute(body, target.local(), true).isEmpty())
            return new TakeSeat(body, hat, target, new Walk(hat, target.local()));
        if (ability.hopReach() <= 0) return null;
        var hop = new Hop(hat, target.local());
        return hop.check(body).ok() ? new TakeSeat(body, hat, target, hop) : null;
    }
    /** A failed move is excluded until a rider has really moved: every rider's place in eighths of a block, not the drifting target. */
    Object world() {
        var places = new ArrayList<Object>(List.of(hat.getUUID(), move.id()));
        for (var rider : hat.getPassengers()) if (rider instanceof YukkuriEntity y) {
            var at = hat.riders().local(y.getUUID());
            places.add(List.of(y.getUUID(), Math.round(at.x * hat.unit() * 8), Math.round(at.z * hat.unit() * 8)));
        }
        return places;
    }
    String why() { return "rest=" + GameplayLog.at(target.world()) + " via=" + move.id(); }

    @Override public String id() { return "take_seat"; }
    @Override public long deadline() { return body.level().getGameTime() + (long) PlayAboard.number("play_deadline"); }
    /** The child reaches its chosen spot by its own movement. */
    @Override public boolean satisfied() {
        if (body.getVehicle() != hat || body.motor().current() instanceof Hop) return false;
        return hat.settled(body) && hat.riders().local(body.getUUID()).distanceTo(target.local()) * hat.unit() <= .02;
    }
    @Override public List<Method> methods() {
        return List.of(Plan.of(id(), () -> body.getVehicle() != hat ? null : List.of(() -> new Step.Do(move))));
    }
    @Override public void ended(IntentRunner.Result result) {
        GameplayLog.limited(body, "aquatic", "take_seat_end", 100, "take_seat end result={} hat={}", result, hat.getUUID());
    }
}
