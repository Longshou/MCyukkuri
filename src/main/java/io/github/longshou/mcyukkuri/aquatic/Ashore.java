package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Right;
import io.github.longshou.mcyukkuri.motor.action.TakeProp;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Off the water with her hat (refactor WP6a), in two forms that share one way:
 * <ul>
 *   <li>{@code come_ashore} (design 8.3 上岸; HARD): from aboard, paddle over calm water to a bank edge (no stick: drift until a bank
 *       is in hop reach), carry each young that cannot hop off in her mouth and hop ashore with it, hop ashore herself (a young
 *       that hops follows by its own {@code follow_ashore}), and take the empty hat back ({@code TakeProp}, {@code hat_on_self_aq}).
 *       Done once she stands ashore wearing it.</li>
 *   <li>{@code resolve_own_hat} (design 8.1, HEADWEAR; SOFT): her hat out in a form she did not choose (left afloat, turned over,
 *       beached) while she is ashore: the same unloading and taking back from the bank, righting it from the bank when it lies crown
 *       up, waiting a while for it to drift in reach; else she goes into the water after it (the escape then swims to her own hat
 *       and climbs aboard). Done once it is worn or ridden. It ends with {@code [headwear] reclaim done} or
 *       {@code reclaim expired reason=}.</li>
 * </ul>
 */
final class Ashore implements Intent {
    /** The whole landing; the whole reclaim; the longest wait for young to hop off or for the hat to drift into reach (ticks). */
    private static final int COME = 2400, RECLAIM = 1600, WAIT = 300;
    /** Blocks from a route leg a paddled hat counts as arrived there; from the bank edge once it is paddled in against it; past
     *  its brim's reach a hat lies out of reach from the bank. */
    private static final double ARRIVE = .6, AGAINST = .5, OUT_OF_REACH = .6;

    private final YukkuriEntity owner;
    private final boolean reclaim;
    private final long started;
    private Waters.Edge landing;
    /** Landings already paddled in against without a hop ashore coming in reach: the next one is another, at most this many. */
    private final Set<BlockPos> tried = new HashSet<>();
    private static final int LANDINGS = 3;

    private Ashore(YukkuriEntity owner, boolean reclaim) {
        this.owner = owner;
        this.reclaim = reclaim;
        this.started = owner.level().getGameTime();
    }

    static Ashore come(YukkuriEntity owner) { return new Ashore(owner, false); }
    static Ashore reclaim(YukkuriEntity owner) { return new Ashore(owner, true); }

    @Override public String id() { return reclaim ? "resolve_own_hat" : "come_ashore"; }
    @Override public long deadline() { return started + (reclaim ? RECLAIM : COME); }
    @Override public Commitment commitment() { return reclaim ? Commitment.SOFT : Commitment.HARD; }

    @Override public boolean satisfied() {
        if (reclaim) return owner.life().custody().worn() || AquaticLife.aboard(owner, AquaticLife.hat(owner));
        return owner.life().custody().worn() && AquaticLife.ashore(owner) && AquaticLife.carried(owner) == null;
    }

    @Override public List<Method> methods() {
        var land = Plan.of(reclaim ? "take_back" : "land", this::derive);
        if (!reclaim) return List.of(land);
        return List.of(land, new Plan("swim_to_hat", this::swimmable, Set.of(Reason.NO_PROGRESS, Reason.NO_CONTACT, Reason.TOO_FAR), this::swim));
    }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.hat(owner);
        if (hat == null || hat.isRemoved()) return null;
        var held = AquaticLife.carried(owner);
        if (AquaticLife.aboard(owner, hat)) {
            if (held != null) return List.of(Ferry.hopAshore(owner));
            var dependent = dependentAboard(hat);
            if (Waters.ashore(owner) != null)
                return dependent != null ? Ferry.grabAboard(owner, hat, dependent) : List.of(Ferry.hopAshore(owner));
            return paddle(hat);
        }
        if (!AquaticLife.ashore(owner)) return null;
        if (held != null) return List.of(() -> new Step.Do(new Release(held)));
        if (hat.upsideDown()) return fromBank(hat, () -> new Step.Do(new Right(hat, "reclaim")), () -> new Step.Wait(hat::steady, 60, "hat_settles"));
        if (AquaticLife.afloat(hat) && dependentAboard(hat) != null) return Ferry.hopAboard(owner, hat);
        if (youngAboard(hat)) return List.of(() -> new Step.Wait(() -> !youngAboard(hat), WAIT, Behaviors.BOAT_CALL));
        return fromBank(hat, () -> new Step.Do(new TakeProp(new HatProp(owner, true, AquaticLife.afloat(hat)))));
    }

    /** Aboard and no bank in hop reach: paddle the next leg toward a landing over calm water; with no stick, drift until one is. */
    private List<Supplier<Step>> paddle(PlacedHatEntity hat) {
        if (!AquaticLife.stick(owner)) return List.of(() -> new Step.Wait(() -> Waters.ashore(owner) != null, WAIT, Behaviors.BOAT_DRIFT));
        if (landing == null && tried.size() >= LANDINGS) return null;
        if (landing == null) landing = Waters.landing(hat, owner, tried);
        var route = landing == null ? List.<Vec3>of() : Waters.route(hat, landing.water());
        Waters.log(hat, owner, landing == null ? null : landing.water(), route, id());
        if (route.isEmpty()) { landing = null; return null; }
        var edge = landing;
        if (route.size() > 1) return List.of(() -> new Step.Do(new Paddle(Waters.leg(route), ARRIVE)));
        // at the landing water: paddle in against the bank, once; no hop in reach even then, and the next derive takes another
        if (!tried.add(edge.stand())) { landing = null; return paddle(hat); }
        return List.of(() -> new Step.Do(new Paddle(edge.edge(), AGAINST)));
    }

    /** From the bank edge nearest the hat: walk there, face the hat, then the closing steps; with no edge near it, wait for drift. */
    @SafeVarargs
    private List<Supplier<Step>> fromBank(PlacedHatEntity hat, Supplier<Step>... then) {
        var edge = Waters.beside(hat, owner);
        if (edge == null && !AquaticLife.afloat(hat) && !hat.upsideDown()) return beached(hat, then);
        if (edge == null || !near(hat, edge))
            return List.of(() -> new Step.Wait(() -> { var e = Waters.beside(hat, owner); return e != null && near(hat, e); }, WAIT, Behaviors.BOAT_CALL));
        var stand = edge.stand(owner.getBbWidth());
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(Walk.stand(stand, 1)));
        steps.add(() -> new Step.Do(new Face(SurfaceApproach.yaw(owner.position(), hat.position()))));
        steps.addAll(List.of(then));
        return steps;
    }

    /** Its brim reaches the bank edge or nearly: a body at the edge holds it with mouth or braid. */
    private static boolean near(PlacedHatEntity hat, Waters.Edge edge) {
        return hat.position().subtract(edge.edge()).horizontalDistance() <= HatGeometry.brim()[1] * hat.unit() + OUT_OF_REACH;
    }

    /** A hat on dry ground: walk up beside it (the family pickup's stand) and the closing steps. */
    @SafeVarargs
    private List<Supplier<Step>> beached(PlacedHatEntity hat, Supplier<Step>... then) {
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> {
            var at = SurfaceApproach.beside(owner, hat, owner.getBbWidth() / 2 + .3, 1.2);
            return at == null ? null : new Step.Do(Walk.stand(at, 1));
        });
        steps.add(() -> new Step.Do(new Face(SurfaceApproach.yaw(owner.position(), hat.position()))));
        steps.addAll(List.of(then));
        return steps;
    }

    /** Her hat afloat within the escape's own-hat range of a bank edge she can reach: going in after it is a way. */
    private Feasibility swimmable() {
        var hat = AquaticLife.hat(owner);
        if (hat == null || hat.distanceTo(owner) > 16 || !AquaticLife.ashore(owner)) return Feasibility.of(Reason.TOO_FAR, 0, "hat_out_of_range");
        return Waters.beside(hat, owner) != null || Waters.berth(owner) != null ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_bank_edge");
    }

    /** Walk to the bank edge toward the hat and hop out over the water at it: in the water the escape swims to her own hat. */
    private List<Supplier<Step>> swim() {
        var hat = AquaticLife.hat(owner);
        if (hat == null || !AquaticLife.ashore(owner)) return null;
        var edge = Waters.beside(hat, owner);
        if (edge == null) edge = Waters.berth(owner);
        if (edge == null) return null;
        var e = edge;
        return List.of(() -> SurfaceApproach.arrived(owner, e.stand(owner.getBbWidth())) ? null : new Step.Do(Walk.stand(e.stand(owner.getBbWidth()), 1)),
                () -> new Step.Do(new Face(e.yaw())),
                () -> new Step.Do(new Hop(e.onWater(.6))));
    }

    /** Her nearest young aboard that cannot hop off by itself. */
    private YukkuriEntity dependentAboard(PlacedHatEntity hat) {
        for (var y : AquaticLife.young(owner)) if (!AquaticLife.jumper(y) && AquaticLife.aboard(y, hat)) return y;
        return null;
    }

    private boolean youngAboard(PlacedHatEntity hat) {
        return AquaticLife.young(owner).stream().anyMatch(y -> AquaticLife.aboard(y, hat));
    }

    @Override public void ended(IntentRunner.Result result) {
        var held = AquaticLife.carried(owner);
        if (result != IntentRunner.Result.DONE && held != null) {
            // what an unfinished carry still holds is put down where it hangs (as Fetch does)
            Motor.dismount(held, held.position(), Vec3.ZERO, id() + "_ended");
            GameplayLog.event(owner, "aquatic", "{} release child={} at={} result={}", id(), held.getUUID(), GameplayLog.at(held.position()), result);
        }
        if (!reclaim) return;
        var hat = AquaticLife.hat(owner);
        if (result == IntentRunner.Result.DONE) {
            GameplayLog.event(owner, "headwear", "reclaim done how={} custody={}", owner.life().custody().worn() ? "worn" : "aboard",
                    owner.life().custody().state());
            return;
        }
        String reason = hat == null || hat.isRemoved() ? "lost" : owner.isInWaterOrBubble() ? "swimming_to_hat"
                : result == IntentRunner.Result.INTERRUPTED ? "interrupted" : owner.level().getGameTime() > deadline() ? "unreachable" : "no_way";
        GameplayLog.event(owner, "headwear", "reclaim {} reason={} result={} hat={} form={} distance={}", reason.equals("swimming_to_hat") ? "handed" : "expired",
                reason, result, hat == null ? "none" : hat.getUUID(), hat == null ? "none" : AquaticGoal.form(hat),
                hat == null ? -1 : Math.round(hat.distanceTo(owner) * 10) / 10.0);
    }
}
