package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/**
 * The night on the water (R6: the family sleeps in the hat boat, not ashore). The captain paddles to still water (her own cell when it
 * is still, else the nearest still cell with a bank close by, {@link Waters#mooring}), drifts there until her body is ready for the
 * night rest, and asks the life layer for it ({@code askCarrierRest}); the fatigue goal then lays her down on the seat and wakes her at
 * dawn, or at the first real need, danger or rain. A young aboard needs no berth: it asks the same once the hull lies still and its
 * body is ready. Done when the ask is made; with no still water in reach, or no way there, it ends EXHAUSTED and she goes ashore
 * for the night as before ({@code night_pull}).
 */
final class NightRest implements Intent {
    /** One evening's settling at most (ticks); one look at whether the body is ready; how near the berth counts as there (blocks). */
    private static final int DEADLINE = 2400, LOOK = 200;
    private static final double ARRIVE = .8;

    private final YukkuriEntity body;
    private final boolean captain;
    private final long started;
    private BlockPos berth;
    private boolean planned;

    NightRest(YukkuriEntity body, boolean captain) {
        this.body = body;
        this.captain = captain;
        this.started = body.level().getGameTime();
    }

    @Override public String id() { return "night_rest"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public boolean satisfied() { return body.life().carrierRestOpen(); }
    @Override public List<Method> methods() { return List.of(Plan.of(captain ? "moor_and_rest" : "rest_aboard", this::derive)); }

    private List<Supplier<Step>> derive() {
        // a young rides its parent's hull, in the hold or on her head
        var parent = captain ? body : AquaticLife.parent(body);
        var hat = parent == null ? null : AquaticLife.hat(parent);
        if (!AquaticLife.afloat(hat) || !AquaticLife.aboard(body, hat)) return null;
        if (captain) {
            if (!planned) {
                planned = true;
                berth = Waters.mooring(hat);
                GameplayLog.event(body, "aquatic", "night_rest berth={} stay={} hat={}", berth == null ? "none" : berth, berth != null && berth.equals(Waters.cell(hat)),
                        hat.getUUID());
            }
            if (berth == null) return null;
            if (Waters.surface(body.level(), berth).subtract(hat.position()).horizontalDistance() > ARRIVE) {
                var route = Waters.route(hat, berth);
                Waters.log(hat, body, berth, route, "night_rest");
                return route.isEmpty() ? null : List.of(() -> new Step.Do(new Paddle(Waters.leg(route), ARRIVE)));
            }
        }
        if (Waters.moored(hat) && body.life().readyForCarrierRest()) {
            body.life().askCarrierRest();
            GameplayLog.event(body, "aquatic", "night_rest asked captain={} hat={} at={} energy={}", captain, hat.getUUID(), GameplayLog.at(hat.position()),
                    Math.round(body.life().energy()));
            return List.of(() -> new Step.Wait(this::satisfied, 40, Behaviors.BOAT_DRIFT));
        }
        // she waits for the hull to lie still and the body to be ready; a look that finds neither ends the wait (not a failure), the next one asks again
        GameplayLog.limited(body, "aquatic", "night_rest.wait", 400, "night_rest waiting captain={} moored={} hold={}", captain, Waters.moored(hat),
                String.valueOf(body.life().carrierRestHold()));
        long until = body.level().getGameTime() + LOOK;
        return List.of(() -> new Step.Wait(() -> body.level().getGameTime() >= until || Waters.moored(hat) && body.life().readyForCarrierRest(),
                LOOK + 20, Behaviors.BOAT_DRIFT));
    }

    @Override public void ended(IntentRunner.Result result) {
        if (result != IntentRunner.Result.DONE) GameplayLog.event(body, "aquatic", "night_rest end result={} captain={} berth={}", result, captain,
                berth == null ? "none" : berth);
    }
}
