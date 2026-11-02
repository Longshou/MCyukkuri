package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;

/** A young on the brim hopping into a seat the hat admits it into now. Nothing is held for it: until one is free the brim is a
 * legal place to be, and the hop is the ordinary shared ability. */
final class TakeSeat implements Intent {
    private final YukkuriEntity body;
    private final PlacedHatEntity hat;

    TakeSeat(YukkuriEntity body, PlacedHatEntity hat) {
        this.body = body;
        this.hat = hat;
    }

    @Override public String id() { return "take_seat"; }
    @Override public long deadline() { return body.level().getGameTime() + (long) PlayAboard.number("play_deadline"); }
    /** Done only once the hop is over and it is at the hat's seat with that seat really available: the shared checks, combined. */
    @Override public boolean satisfied() {
        if (body.getVehicle() != hat || body.motor().current() instanceof Hop) return false;
        return hat.seated(body);
    }
    @Override public List<Method> methods() {
        return List.of(Plan.of(id(), () -> body.getVehicle() != hat ? null : List.of(() -> new Step.Do(new Hop(hat)))));
    }
    @Override public void ended(IntentRunner.Result result) {
        GameplayLog.limited(body, "aquatic", "take_seat_end", 100, "take_seat end result={} hat={}", result, hat.getUUID());
    }
}
