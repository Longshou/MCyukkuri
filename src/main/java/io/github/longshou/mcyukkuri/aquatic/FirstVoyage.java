package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
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
 * A young's first voyage on its own hat (初航; AQUATIC 家庭、照护与初航, CHILDHOOD 水生幼体): old enough and able (solid food, hopping,
 * learning, a fifth of an adult's size), wearing its own hat with a stick for it, its parent aboard her boat near it on a calm day,
 * it tries its own hat as a boat. Off its parent's hat onto the bank, it sets its hat down on the water and boards it (its own
 * {@code set_out}), paddles out round a point of open water by its parent's boat, comes back to the bank and takes its hat back
 * (its own {@code come_ashore}). Back ashore wearing it, it sails from then on ({@code ChildhoodLife.learnSailing}). A fall into the
 * water on the way is real: it cannot climb its own hat yet, so it struggles until its parent lifts it out, and the voyage has
 * failed. HARD, a short commitment only danger breaks; whether it is tried at all is the young's desire (P11).
 */
final class FirstVoyage implements Intent {
    /** The whole voyage (ticks); how far from the parent's boat (blocks) the point it rounds lies; within how far of it it counts as rounded. */
    private static final int DEADLINE = 4800, MIN = 2, MAX = 4;
    private static final double ARRIVE = .8;

    private final YukkuriEntity young, parent;
    private final long started;
    private BlockPos turn;
    private boolean rounded, begun;

    FirstVoyage(YukkuriEntity young, YukkuriEntity parent) {
        this.young = young;
        this.parent = parent;
        this.started = young.level().getGameTime();
    }

    @Override public String id() { return "first_voyage"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public Commitment commitment() { return Commitment.HARD; }
    @Override public boolean satisfied() { return rounded && young.life().custody().worn() && AquaticLife.ashore(young); }
    @Override public List<Method> methods() { return List.of(Plan.of("own_hat", this::derive)); }

    private List<Supplier<Step>> derive() {
        if (!begun) {
            begun = true;
            var home = AquaticLife.hat(parent);
            GameplayLog.event(young, "aquatic", "first_voyage start parent={} parent_hat={} scale={} stick={} from={}", parent.getUUID(),
                    home == null ? "none" : home.getUUID(), young.family().scale(), AquaticLife.stick(young), AquaticLife.ashore(young) ? "bank" : "parent_hat");
        }
        if (young.isInWaterOrBubble()) return null;
        var own = AquaticLife.hat(young);
        if (own == null) {
            if (rounded) return null;
            if (AquaticLife.riding(young) != null) return List.of(Ferry.hopAshore(young));
            return AquaticLife.ashore(young) ? List.of(() -> new Step.Sub(new SetOut(young, stayed -> {}))) : null;
        }
        if (!AquaticLife.aboard(young, own))
            return AquaticLife.ashore(young) ? List.of(() -> new Step.Sub(rounded ? Ashore.come(young) : new SetOut(young, stayed -> {}))) : null;
        if (!AquaticLife.afloat(own)) return null;
        if (!rounded) {
            var home = AquaticLife.hat(parent);
            if (turn == null) turn = Waters.wander(AquaticLife.afloat(home) ? home : own, MIN, MAX);
            if (turn == null) return null;
            if (Waters.surface(own.level(), turn).subtract(own.position()).horizontalDistance() > ARRIVE) {
                var route = Waters.route(own, turn);
                Waters.log(own, young, turn, route, id());
                return route.isEmpty() ? null : List.of(() -> new Step.Do(new Paddle(Waters.leg(route), ARRIVE)));
            }
            rounded = true;
            GameplayLog.event(young, "aquatic", "first_voyage rounded at={} parent_distance={}", turn.toShortString(),
                    Math.round(own.distanceTo(parent) * 10) / 10.0);
        }
        return List.of(() -> new Step.Sub(Ashore.come(young)));
    }

    @Override public void ended(IntentRunner.Result result) {
        var own = AquaticLife.hat(young);
        if (result == IntentRunner.Result.DONE) {
            young.childhood().learnSailing(id());
            GameplayLog.event(young, "aquatic", "first_voyage success parent={} ticks={}", parent.getUUID(), young.level().getGameTime() - started);
            return;
        }
        GameplayLog.event(young, "aquatic", "first_voyage fail result={} rounded={} in_water={} own_hat={} at={}", result, rounded,
                young.isInWaterOrBubble(), own == null ? young.life().custody().worn() ? "worn" : "none" : AquaticGoal.form(own), GameplayLog.at(young.position()));
    }
}
