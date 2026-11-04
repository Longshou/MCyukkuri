package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/**
 * A young's first voyage on its own hat (初航; AQUATIC 家庭、照护与初航, CHILDHOOD 水生幼体): old enough and able (solid food, hopping,
 * learning, the shared young-seat limit), wearing its own hat with a stick for it, its parent aboard her boat near it on a calm day,
 * it tries its own hat as a boat. Off its parent's hat it sets out by whichever way is physically open: onto the bank when a hop
 * reaches one (it sets its hat down on the water from there and boards it, its own {@code set_out}), else from her brim, setting
 * its hat down on the calm water beside her hull and hopping onto it from hull to hull. It paddles out round a point of open water
 * by its parent's boat, comes back to the bank and takes its hat back (its own {@code come_ashore}). Back ashore wearing it, it
 * sails from then on ({@code ChildhoodLife.learnSailing}). A fall into the
 * water on the way is real: it cannot climb its own hat yet, so it struggles toward the bank and the voyage has failed. HARD, a short
 * commitment only danger breaks; whether it is tried at all is the young's desire (P11). Its one way is
 * tried once: a step that fails, or no way on from where things are, ends the voyage (no re-entry), and the goal lets the young
 * try again only the next day.
 */
final class FirstVoyage implements Intent {
    /** The whole voyage (ticks); how far from the parent's boat (blocks) the point it rounds lies; within how far of it it counts as rounded. */
    private static final int DEADLINE = 4800, MIN = 2, MAX = 4;
    private static final double ARRIVE = .8;

    private final YukkuriEntity young, parent;
    private final Supplier<IntentRunner> runner;
    private final Runnable failed;
    private final long started;
    private BlockPos turn;
    private boolean rounded, begun, stuck;
    /** How it left its parent's hat: "bank" or "brim"; "none" until it has. */
    private String route = "none";

    /** {@code runner}: the run this voyage is the intent of (its counted failures); {@code failed}: told once it ends unfinished. */
    FirstVoyage(YukkuriEntity young, YukkuriEntity parent, Supplier<IntentRunner> runner, Runnable failed) {
        this.young = young;
        this.parent = parent;
        this.runner = runner;
        this.failed = failed;
        this.started = young.level().getGameTime();
    }

    @Override public String id() { return "first_voyage"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public Commitment commitment() { return Commitment.HARD; }
    @Override public boolean satisfied() {
        return (young.motor().current() == null || young.motor().current().interruptible())
                && rounded && young.life().custody().worn() && AquaticLife.ashore(young);
    }
    @Override public List<Method> methods() {
        return List.of(new Plan("own_hat", () -> stuck ? Feasibility.of(Reason.NO_PROGRESS, 0, "voyage_failed") : Feasibility.pass(),
                Set.of(Reason.NO_PROGRESS), () -> { var steps = derive(); stuck |= steps == null; return steps; }));
    }

    private List<Supplier<Step>> derive() {
        if (!begun) {
            begun = true;
            var home = AquaticLife.hat(parent);
            GameplayLog.event(young, "aquatic", "first_voyage start parent={} parent_hat={} scale={} stick={} from={}", parent.getUUID(),
                    home == null ? "none" : home.getUUID(), young.family().scale(), AquaticLife.stick(young), AquaticLife.ashore(young) ? "bank" : "parent_hat");
        }
        var run = runner.get();
        if (young.isInWaterOrBubble() || run != null && run.attempts().used() > 0) return null;
        var own = AquaticLife.hat(young);
        var ride = AquaticLife.riding(young);
        if (own == null) {
            if (rounded) return null;
            if (ride != null) return leave(ride);
            return AquaticLife.ashore(young) ? List.of(() -> new Step.Sub(new SetOut(young, stayed -> {}))) : null;
        }
        if (!AquaticLife.aboard(young, own)) {
            if (ride != null) return List.of(() -> new Step.Wait(own::steady, 60, "hat_settles"), () -> new Step.Do(new Hop(own)),
                    () -> { note("hopped onto own hat"); return null; });
            return AquaticLife.ashore(young) ? List.of(() -> new Step.Sub(rounded ? Ashore.come(young) : new SetOut(young, stayed -> {}))) : null;
        }
        if (!AquaticLife.afloat(own)) return null;
        if (!rounded) {
            if (turn == null) turn = Waters.wander(own, MIN, MAX);
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

    /** Off its parent's hat, whichever way is physically open: onto the bank when a hop reaches one, else its own hat set down on the
     *  water beside her hull from her brim (along the brim to the launch place first when it stands elsewhere), then hopped onto
     *  from hull to hull. */
    private List<Supplier<Step>> leave(PlacedHatEntity ride) {
        if (Waters.brimAshore(ride, young) != null) {
            route = "bank";
            GameplayLog.event(young, "aquatic", "first_voyage route=bank reason=bank_in_hop_reach hat={}", ride.getUUID());
            return List.of(Ferry.hopAshore(young));
        }
        var launch = Waters.brimLaunch(young, ride);
        GameplayLog.event(young, "aquatic", "first_voyage route={} reason=no_bank_in_hop_reach hat={} water={}", launch == null ? "none" : "brim",
                ride.getUUID(), launch == null ? "none" : launch.edge().water().toShortString());
        if (launch == null) return null;
        route = "brim";
        var edge = launch.edge();
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> Waters.standsAt(ride, young, launch.stop()) ? null : new Step.Do(new Hop(ride, launch.stop())));
        steps.add(() -> new Step.Do(new Face(edge.yaw())));
        steps.add(() -> new Step.Do(new PlaceProp(new HatProp(young, true, true), Waters.hatAt(edge, young), edge.yaw() + (float) HullBounds.BOARDING_YAW)));
        steps.add(() -> { note("hat set on water"); return null; });
        return steps;
    }

    /** What the finished step before it left behind: said once, the step itself is the shared action's. */
    private void note(String what) {
        var own = AquaticLife.hat(young);
        GameplayLog.event(young, "aquatic", "first_voyage {} route={} hat={} at={} aboard={}", what, route, own == null ? "none" : own.getUUID(),
                GameplayLog.at(own == null ? young.position() : own.position()), AquaticLife.aboard(young, own));
    }

    @Override public void ended(IntentRunner.Result result) {
        var own = AquaticLife.hat(young);
        if (result == IntentRunner.Result.DONE) {
            young.childhood().learnSailing(id());
            // Back ashore with its own hat on, its first voyage behind it (初航成功的雀跃).
            young.showReaction("happy", "hello", 60);
            GameplayLog.event(young, "aquatic", "first_voyage success parent={} route={} ticks={}", parent.getUUID(), route, young.level().getGameTime() - started);
            return;
        }
        GameplayLog.event(young, "aquatic", "first_voyage fail result={} route={} rounded={} in_water={} own_hat={} at={}", result, route, rounded,
                young.isInWaterOrBubble(), own == null ? young.life().custody().worn() ? "worn" : "none" : AquaticGoal.form(own), GameplayLog.at(young.position()));
        failed.run();
    }
}
