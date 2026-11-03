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
        var at = hat.riders().local(body.getUUID());
        var ability = BodyAbility.of(body);
        if (ability.walkSpeed() <= 0 && ability.hopReach() <= 0) return null;
        var target = hat.restSpot(body);
        if (target == null || !hat.profile().inOpening(target.local().x, target.local().z) || !target.available(body)) return null;
        if (at.distanceTo(target.local()) * hat.unit() <= .02) return null;
        if (ability.walkSpeed() > 0 && !hat.walkRoute(body, target.local(), true).isEmpty())
            return new TakeSeat(body, hat, target, new Walk(hat, target.local()));
        if (ability.hopReach() <= 0) return null;
        var hop = new Hop(hat, target.local());
        return hop.check(body).ok() ? new TakeSeat(body, hat, target, hop) : null;
    }
    /** Hull motion in world space does not change a failed local move. */
    Object world() { return List.of(hat.getUUID(), target.local(), hat.riders().local(body.getUUID()), move.id()); }
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
