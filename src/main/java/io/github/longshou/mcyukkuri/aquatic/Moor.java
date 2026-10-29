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
import net.minecraft.world.phys.Vec3;

/**
 * The night (or rain) at a mooring with no rope (refactor WP6a, AQUATIC 无绳夜泊, project design): she paddles into a sheltered
 * calm cell (a bank notch, a covered spot) and lets the bank hold the hull; her young creep into the hollow by their own
 * {@code nestle} desire while she leans over them ({@code boat_cover_young}), or she sleeps ({@code boat_sleep}). She looks up every
 * 200 ticks; drifted off the berth she wakes and paddles back in small strokes, at most twice, then sleeps adrift in the calm
 * water. With no notch, or no stick, she sleeps where she floats. Done in the morning out of the rain: {@code [aquatic] boat_wake}.
 */
final class Moor implements Intent {
    /** One night at most (ticks); one sleep between looks; how far (blocks) off the berth wakes her, and counts as in it. */
    private static final int DEADLINE = 13000, LOOK = 200, RESTATIONS = 2;
    private static final double DRIFTED = 3, IN = .6;

    private final YukkuriEntity owner;
    private final long started;
    private BlockPos notch;
    private Vec3 berth;
    private boolean arrived;
    private int restations;

    Moor(YukkuriEntity owner) {
        this.owner = owner;
        this.started = owner.level().getGameTime();
    }

    @Override public String id() { return "moor"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public boolean satisfied() { return !AquaticLife.dusk(owner.level()) && !AquaticLife.rain(owner); }
    @Override public List<Method> methods() { return List.of(Plan.of("moor", this::derive)); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.hat(owner);
        if (!AquaticLife.aboard(owner, hat) || !AquaticLife.afloat(hat)) return null;
        if (berth == null) {
            notch = AquaticLife.stick(owner) ? Waters.notch(hat) : null;
            berth = notch == null ? hat.position() : Waters.surface(owner.level(), notch);
            GameplayLog.event(owner, "aquatic", "moor berth={} sheltered={} stick={} drift_sleep={}", GameplayLog.at(berth),
                    notch == null ? 0 : Waters.shelter(owner.level(), notch), AquaticLife.stick(owner), notch == null);
        }
        double off = hat.position().subtract(berth).horizontalDistance();
        arrived |= off <= IN;
        if (notch != null && off > (arrived ? DRIFTED : IN)) {
            var route = AquaticLife.stick(owner) && (!arrived || restations < RESTATIONS) ? Waters.route(hat, notch) : List.<Vec3>of();
            Waters.log(hat, owner, notch, route, "moor");
            if (!route.isEmpty()) {
                if (arrived) {
                    restations++;
                    GameplayLog.event(owner, "aquatic", "moor restation off={} count={}/{}", Math.round(off * 10) / 10.0, restations, RESTATIONS);
                }
                return List.of(() -> new Step.Do(new Paddle(Waters.leg(route), IN)));
            }
            // no way back in (no stick, no route, the restations spent): she sleeps where she drifted
            GameplayLog.event(owner, "aquatic", "moor drift_sleep off={} restations={}/{} stick={}", Math.round(off * 10) / 10.0, restations,
                    RESTATIONS, AquaticLife.stick(owner));
            notch = null;
            berth = hat.position();
        }
        long until = owner.level().getGameTime() + LOOK;
        boolean young = AquaticLife.young(owner).stream().anyMatch(y -> AquaticLife.aboard(y, hat)), watch = notch != null;
        var at = berth;
        return List.of(() -> new Step.Wait(() -> satisfied() || owner.level().getGameTime() >= until
                || watch && hat.position().subtract(at).horizontalDistance() > DRIFTED, LOOK + 20,
                young ? Behaviors.BOAT_COVER_YOUNG : Behaviors.BOAT_SLEEP));
    }

    @Override public void ended(IntentRunner.Result result) {
        var hat = AquaticLife.hat(owner);
        if (result == IntentRunner.Result.DONE) GameplayLog.event(owner, "aquatic", "boat_wake hat={} at={} restations={} night_ticks={}",
                hat == null ? "none" : hat.getUUID(), GameplayLog.at(owner.position()), restations, owner.level().getGameTime() - started);
        else GameplayLog.event(owner, "aquatic", "moor end result={} restations={}", result, restations);
    }
}
