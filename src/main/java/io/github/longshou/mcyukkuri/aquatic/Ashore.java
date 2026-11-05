package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.EscapeWater;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Right;
import io.github.longshou.mcyukkuri.motor.action.StepIn;
import io.github.longshou.mcyukkuri.motor.action.TakeProp;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Land with the family, take the empty hat back, or reclaim it from a reachable bank.
 * The landing brings every young of hers off the hull: docked, she takes up each one that cannot leave by itself while she still has
 * a hold for it (one already in her braid, the next in her mouth), carries them ashore with those riding her head and sets each down;
 * one still on the hull she takes over the rim from the bank, or boards for when no bank stand reaches it, and only an empty hat is
 * taken back.
 * A sailor without a bank action swims directly to the hat; an unsailed young yields to normal goals. */
final class Ashore implements Intent {
    /** The whole landing and unload; the whole reclaim; the longest wait for young to hop off or for the hat to drift into reach (ticks). */
    private static final int COME = 2400, RECLAIM = 1600, WAIT = 300;
    /** Route-leg arrival distance. */
    private static final double ARRIVE = .6;

    private final YukkuriEntity owner;
    private enum Purpose { COME, RECLAIM }
    private final Purpose purpose;
    private final long started;
    private Waters.Edge landing;
    /** Landings already paddled in against without a hop ashore coming in reach: the next one is another, at most this many. */
    private final Set<BlockPos> tried = new HashSet<>();
    private static final int LANDINGS = 3;
    private final Ferry.Beckoning beckoning = new Ferry.Beckoning();
    /** The one water way has been derived; ending anywhere but aboard gives this reclaim up. */
    private boolean waterTried;

    private Ashore(YukkuriEntity owner, Purpose purpose) {
        this.owner = owner;
        this.purpose = purpose;
        this.started = owner.level().getGameTime();
    }

    static Ashore come(YukkuriEntity owner) { return new Ashore(owner, Purpose.COME); }
    static Ashore reclaim(YukkuriEntity owner) { return new Ashore(owner, Purpose.RECLAIM); }
    private boolean reclaim() { return purpose == Purpose.RECLAIM; }

    /** An unsailed young only asks for its hat when it can do something from a reachable stand. */
    static boolean canReclaim(YukkuriEntity body, PlacedHatEntity hat) {
        return body.childhood().canSail() || (!AquaticLife.onWater(hat)
                ? SurfaceApproach.beside(body, hat, body.getBbWidth() / 2 + .3, 1.2) != null : Waters.bankEdge(body, hat) != null);
    }

    @Override public String id() { return reclaim() ? "resolve_own_hat" : "come_ashore"; }
    @Override public boolean ownsBodyInWater() { return reclaim() && waterTried; }
    @Override public long deadline() { return started + (reclaim() ? RECLAIM : COME); }
    @Override public Commitment commitment() { return reclaim() ? Commitment.SOFT : Commitment.HARD; }

    @Override public boolean satisfied() {
        if (owner.motor().current() != null && !owner.motor().current().interruptible()) return false;
        var hat = AquaticLife.hat(owner);
        if (reclaim()) return owner.life().custody().worn() || hat != null && hat.settled(owner);
        return owner.life().custody().worn() && AquaticLife.ashore(owner) && AquaticLife.carried(owner) == null;
    }

    @Override public List<Method> methods() {
        var land = reclaim() ? Plan.of("take_back", this::beckonable, this::derive) : Plan.of("land", this::derive);
        if (!reclaim()) return List.of(land);
        if (waterTried) return List.of();
        return List.of(land, new Plan("swim_to_hat", this::swimmable, Set.of(Reason.NO_PROGRESS, Reason.NO_CONTACT, Reason.TOO_FAR), this::swim));
    }

    /** A reachable stand with a real contact or stroke; otherwise the water method is considered immediately. */
    private Feasibility beckonable() {
        var hat = AquaticLife.hat(owner);
        if (hat == null || hat.isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "hat_gone");
        if (!AquaticLife.onWater(hat)) return Feasibility.pass();
        return Waters.bankEdge(owner, hat) != null ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_bank_action");
    }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.hat(owner);
        if (hat == null || hat.isRemoved()) return null;
        var held = AquaticLife.carried(owner);
        var dependent = dependentAboard(hat);
        String carryProblem = dependent == null ? null : ParentCarry.capacityProblem(owner, dependent);
        if (AquaticLife.aboard(owner, hat)) {
            var hop = youngCanLand(hat) ? Ferry.hopAshore(owner).get() : null;
            if (hop == null) return paddle(hat);
            // Docked: a young that cannot leave by itself is taken up while she still has a hold for it, whatever she holds already.
            if (dependent != null && carryProblem == null) return Ferry.grabAboard(owner, hat, dependent);
            if (held == null && dependent == null && youngAboard(hat))
                return List.of(() -> new Step.Wait(() -> !youngAboard(hat), WAIT, Behaviors.BOAT_CALL));
            if (carryProblem != null) GameplayLog.limited(owner, "aquatic", "land.carry", WAIT,
                    "land without dependent child={} reason={}", dependent.getUUID(), carryProblem);
            return List.of(() -> hop);
        }
        if (!AquaticLife.ashore(owner)) return null;
        if (held != null) return List.of(() -> new Step.Do(new Release(held)));
        for (var passenger : owner.getPassengers()) if (passenger instanceof YukkuriEntity child)
            return List.of(() -> new Step.Do(new Release(child)));
        if (hat.upsideDown()) return fromBank(hat, () -> new Right(hat, "reclaim"), () -> new Step.Wait(hat::steady, 60, "hat_settles"));
        if (AquaticLife.afloat(hat) && dependent != null) {
            // This pickup is for carrying it ashore. Do not go for it only to be unable to carry the same child.
            if (carryProblem != null) {
                GameplayLog.limited(owner, "aquatic", "land.pickup", WAIT,
                        "land pickup refused child={} reason={}", dependent.getUUID(), carryProblem);
                return null;
            }
            // A young on the brim by the bank is taken over the rim from a bank stand a hold reaches it from (the Grab's own
            // reach); one out of reach from the bank she boards for and takes from her seat.
            var stand = new Grab(dependent).contactStand(owner);
            if (stand == null) return Ferry.hopAboard(owner, hat, beckoning);
            return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(Walk.stand(stand, 1)),
                    () -> new Step.Do(new Grab(dependent)));
        }
        if (youngAboard(hat)) return List.of(() -> new Step.Wait(() -> !youngAboard(hat), WAIT, Behaviors.BOAT_CALL));
        return fromBank(hat, () -> new TakeProp(new HatProp(owner, true, AquaticLife.afloat(hat))));
    }

    /** Aboard and no bank in hop reach: paddle the next leg toward a landing over calm water. */
    private List<Supplier<Step>> paddle(PlacedHatEntity hat) {
        if (landing == null && tried.size() >= LANDINGS) return null;
        if (landing == null) landing = Waters.landing(hat, owner, tried);
        var route = landing == null ? List.<Vec3>of() : Waters.route(hat, landing.water());
        Waters.log(hat, owner, landing == null ? null : landing.water(), route, id());
        if (route.isEmpty()) { landing = null; return null; }
        var edge = landing;
        if (route.size() > 1) return List.of(() -> new Step.Do(new Paddle(Waters.leg(route), ARRIVE)));
        // at the landing water: paddle in against the bank, once; no hop in reach even then, and the next derive takes another
        if (!tried.add(edge.stand())) { landing = null; return paddle(hat); }
        var dock = edge.out(HullAfloat.brimToward(hat, edge.edge()) + standoff(hat, edge));
        return List.of(() -> new Step.Do(new Paddle(() -> dock,
                distance -> distance <= ARRIVE && Ferry.hopAshore(owner).get() != null && youngCanLand(hat))));
    }

    /** How far the hull docks off the bank so that no rider's collision box reaches into it: the farthest horizontal corner of any
     *  rider's box from the hull centre, past the rim toward the bank (the box corners of a brim place stick out of the round rim,
     *  and the hull turns on its way in, so the bound does not depend on its yaw), plus the hull's own clearance. */
    private static double standoff(PlacedHatEntity hat, Waters.Edge edge) {
        double reach = 0;
        for (var rider : hat.getPassengers()) {
            var box = rider.getBoundingBox();
            for (double x : new double[]{box.minX, box.maxX}) for (double z : new double[]{box.minZ, box.maxZ})
                reach = Math.max(reach, Math.hypot(x - hat.getX(), z - hat.getZ()));
        }
        return Math.max(0, reach - HullAfloat.brimToward(hat, edge.edge())) + HullBounds.EPSILON;
    }

    /** The family landing must also be reachable by the young who will leave under their own power. */
    private boolean youngCanLand(PlacedHatEntity hat) {
        for (var child : AquaticLife.young(owner))
            if (child.getVehicle() == hat && AquaticLife.jumper(child) && Ferry.hopAshore(child).get() == null) return false;
        return true;
    }

    /** From the bank edge nearest the hat: walk there, face the hat, then the closing action ({@code close}, then {@code after}).
     *  On the water and not yet in its grip, the hat is drawn in to the bank with the braid first (Beckon). A reclaim whose hat
     *  drifted out of the braid's reach has no bank way then ({@code swim_to_hat} takes over); a coming-ashore owner calls for it
     *  and waits for it to drift in (the player's nudge too), saying why once. */
    @SafeVarargs
    private List<Supplier<Step>> fromBank(PlacedHatEntity hat, Supplier<Action> close, Supplier<Step>... after) {
        var edge = Waters.bankEdge(owner, hat);
        if (edge == null) {
            if (!AquaticLife.onWater(hat)) return beached(hat, close, after);
            return reclaim() ? null : List.of(() -> waiting(hat, null));
        }
        var approach = bankApproach(owner, hat, edge);
        if (!approach.isEmpty()) return approach;
        if (ContactGeometry.reachesHull(owner, hat, true, false) || !AquaticLife.onWater(hat)) {
            var closing = new ArrayList<Supplier<Step>>();
            closing.add(() -> new Step.Do(close.get()));
            closing.addAll(List.of(after));
            return closing;
        }
        // At the bank facing it: one stroke if the braid really reaches water from here (the action's own check, the same one that
        // admits it); without a stroke a reclaim has no bank way and goes into the water after the hat from this same stand, and a
        // coming-ashore owner calls for it to drift in.
        Step stroke = beckoning.step(owner, hat);
        if (stroke != null) return List.of(() -> stroke);
        if (!reclaim()) return List.of(() -> waiting(hat, edge));
        return owner.childhood().canSail() ? swim() : null;
    }

    /** The steps onto the bank edge nearest the hat and turned to it: walk there, then turn ({@code Face}, which completes on the body
     *  yaw, so a turn already made is never issued again). Empty when she stands there already turned, which is what a bank reach or a
     *  stroke is read from. */
    private static List<Supplier<Step>> bankApproach(YukkuriEntity owner, PlacedHatEntity hat, Waters.Edge edge) {
        var stand = edge.stand(owner.getBbWidth());
        if (owner.position().multiply(1, 0, 1).distanceTo(stand.multiply(1, 0, 1)) >= .25)
            return List.of(() -> new Step.Do(Walk.stand(stand, 1)));
        float yaw = SurfaceApproach.yaw(owner.position(), hat.position());
        if (Math.abs(Mth.wrapDegrees(yaw - owner.yBodyRot)) > Face.FACE_ARRIVED)
            return List.of(() -> new Step.Do(new Face(yaw)));
        return List.of();
    }

    /** Waiting for a hat out of the braid's reach to drift in, said once with its real reason and the calling clip. */
    private Step waiting(PlacedHatEntity hat, Waters.Edge edge) {
        GameplayLog.limited(owner, "headwear", "reclaim.wait", WAIT, "reclaim waiting reason={} hat={} form={} distance={}",
                edge == null ? "no_bank_edge" : "out_of_reach", hat.getUUID(), AquaticGoal.form(hat),
                Math.round(hat.distanceTo(owner) * 10) / 10.0);
        return new Step.Wait(() -> edge == null ? Waters.bankEdge(owner, hat) != null
                : ContactGeometry.reachesHull(owner, hat, true, false) || beckoning.near(owner, hat), WAIT, Behaviors.BOAT_CALL);
    }

    /** A hat on dry ground: walk up beside it (the family pickup's stand), then the closing action. */
    @SafeVarargs
    private List<Supplier<Step>> beached(PlacedHatEntity hat, Supplier<Action> close, Supplier<Step>... after) {
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> {
            var at = SurfaceApproach.beside(owner, hat, owner.getBbWidth() / 2 + .3, 1.2);
            return at == null ? null : new Step.Do(Walk.stand(at, 1));
        });
        steps.add(() -> new Step.Do(new Face(SurfaceApproach.yaw(owner.position(), hat.position()))));
        steps.add(() -> new Step.Do(close.get()));
        steps.addAll(List.of(after));
        return steps;
    }

    /** Her hat afloat within the escape's own-hat range of a bank edge she can reach: going in after it is a way, for one that sails
     *  (one that does not could only struggle in the water). */
    private Feasibility swimmable() {
        var hat = AquaticLife.hat(owner);
        if (!owner.childhood().canSail()) return Feasibility.of(Reason.NO_TOOL, 0, "cannot_sail");
        if (hat == null || hat.distanceTo(owner) > 16 || !AquaticLife.ashore(owner)) return Feasibility.of(Reason.TOO_FAR, 0, "hat_out_of_range");
        return Waters.beside(hat, owner) != null || Waters.shore(owner) != null ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_bank_edge");
    }

    /** Walk to the bank edge nearest the hat, step off it, then keep this intent's ownership through the swim and boarding. */
    private List<Supplier<Step>> swim() {
        var hat = AquaticLife.hat(owner);
        if (waterTried || hat == null || !AquaticLife.ashore(owner)) return null;
        var edge = Waters.beside(hat, owner);
        if (edge == null) edge = Waters.shore(owner);
        if (edge == null) return null;
        waterTried = true;
        var e = edge;
        return List.of(() -> SurfaceApproach.arrived(owner, e.stand(owner.getBbWidth())) ? null : new Step.Do(Walk.stand(e.stand(owner.getBbWidth()), 1)),
                () -> new Step.Do(new StepIn(Waters.surface(owner.level(), e.water()))),
                () -> new Step.Sub(new EscapeWater(owner, new HashMap<>())));
    }

    /** Her nearest young aboard that cannot get off the hull by itself: one in the hollow that cannot hop has to be taken up
     *  first. One already riding her head is carried already — it comes ashore with her and her braid sets it down there. */
    private YukkuriEntity dependentAboard(PlacedHatEntity hat) {
        for (var y : AquaticLife.young(owner)) if (y.getVehicle() == hat && !AquaticLife.jumper(y)) return y;
        return null;
    }

    private boolean youngAboard(PlacedHatEntity hat) {
        return AquaticLife.young(owner).stream().anyMatch(y -> y.getVehicle() == hat);
    }

    @Override public void ended(IntentRunner.Result result) {
        var held = AquaticLife.carried(owner);
        if (result != IntentRunner.Result.DONE && held != null) {
            owner.family().carry().finish(held, id() + "_ended");
        }
        if (!reclaim()) return;
        var hat = AquaticLife.hat(owner);
        if (result == IntentRunner.Result.DONE) {
            GameplayLog.event(owner, "headwear", "reclaim done how={} custody={}", owner.life().custody().worn() ? "worn" : "aboard",
                    owner.life().custody().state());
            return;
        }
        String reason = hat == null || hat.isRemoved() ? "lost" : result == IntentRunner.Result.INTERRUPTED ? "interrupted"
                : owner.level().getGameTime() > deadline() ? "unreachable" : "no_way";
        GameplayLog.event(owner, "headwear", "reclaim expired reason={} result={} hat={} form={} distance={}",
                reason, result, hat == null ? "none" : hat.getUUID(), hat == null ? "none" : AquaticGoal.form(hat),
                hat == null ? -1 : Math.round(hat.distanceTo(owner) * 10) / 10.0);
    }
}
