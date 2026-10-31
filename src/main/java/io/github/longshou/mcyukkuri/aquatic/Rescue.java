package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Lifting a young of hers out of the water (落水他救; AQUATIC 落水、救助与靠岸), the intent of a helper who
 * accepted the young's incident (one decision per helper and incident, SOFT). The faller keeps working its own way out
 * until it is held; only the one who holds it moves it (a held body takes no second grab). Her ways:
 * <ul>
 *   <li>from her own hat afloat ({@code lift}): paddle near (with her stick; without one she waits for it to drift or swim near), walk
 *       on the hat to the side nearest it, take it with the mouth or the braid tip (Grab picks the hold that reaches), and put it
 *       down over her hollow, where it drops in. A hollow without floor for it gets room first: the portion that feeds least goes
 *       over the side, a real loss that floats and may be fished up again.</li>
 *   <li>from the bank ({@code from_bank}, ashore): only a faller within reach of an edge she can stand at: walk there, take it, step
 *       back from the water and put it down on the dry ground; she does not go into the water.</li>
 * </ul>
 * A run that tried its way and still ended without the faller out lets go where it holds it and counts one failure for this
 * helper on this incident (the goal ends her part after three, AQUATIC 同一救助者三次后不再尝试); a run that found no way to try
 * keeps her decision open, so she takes the incident up again once she has a way (boards her hat, reaches a bank edge).
 */
final class Rescue implements Intent {
    /** The longest rescue (ticks), the longest wait for the faller to come within reach, the portions tipped out for room. */
    private static final int DEADLINE = 1200, WAIT = 60, ROOM = 2;

    private final YukkuriEntity helper, faller;
    private final String incident;
    private final Consumer<String> failed;
    private final long started;
    /** Some way gave a step to take in this run (so an exhausted run is a real failure, not a run with no way). */
    private boolean acted;
    private Waters.Edge edge;
    /** Portions tipped over the side for its room in this rescue (at most {@link #ROOM}: more food is not what fills the hollow). */
    private int dumped;
    private double distance;

    Rescue(YukkuriEntity helper, YukkuriEntity faller, String incident, Consumer<String> failed) {
        this.helper = helper;
        this.faller = faller;
        this.incident = incident;
        this.failed = failed;
        this.started = helper.level().getGameTime();
        this.distance = helper.distanceTo(faller);
    }

    String incident() { return incident; }
    YukkuriEntity faller() { return faller; }

    @Override public String id() { return "rescue"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public Commitment commitment() { return Commitment.SOFT; }

    /** Out of the water and let go of: standing, or aboard (its own feet on a hat, not hanging from her). */
    @Override public boolean satisfied() {
        return faller.isAlive() && !faller.isInWaterOrBubble() && faller.getVehicle() != helper && (faller.onGround() || faller.isPassenger());
    }

    @Override public List<Method> methods() {
        return List.of(new Plan("lift", this::aboard, Set.of(Reason.NO_PROGRESS, Reason.NO_CONTACT, Reason.TOO_FAR, Reason.TARGET_MOVED), () -> acting(lift())),
                new Plan("from_bank", this::ashore, Set.of(Reason.NO_PROGRESS, Reason.NO_CONTACT, Reason.TARGET_MOVED), () -> acting(fromBank())));
    }

    private List<Supplier<Step>> acting(List<Supplier<Step>> steps) {
        acted |= steps != null && !steps.isEmpty();
        return steps;
    }

    private Feasibility aboard() {
        return AquaticLife.riding(helper) != null ? Feasibility.pass()
                : Feasibility.of(Reason.NO_SUPPORT, 0, "not_aboard_own_hat");
    }

    private Feasibility ashore() { return AquaticLife.ashore(helper) ? Feasibility.pass() : Feasibility.of(Reason.NO_SUPPORT, 0, "not_ashore"); }

    private static Step act(Action action) { return new Step.Do(action); }

    /** Gone or dead: the grab says so ({@code TARGET_GONE}) and the intent ends lost. */
    private List<Supplier<Step>> lost() { return List.of(() -> act(new Grab(faller))); }

    private List<Supplier<Step>> lift() {
        progress();
        if (!faller.isAlive()) return lost();
        var hat = AquaticLife.riding(helper);
        if (hat == null) return null;
        if (faller.getVehicle() == helper) return Ferry.releaseAboard(helper, hat, faller);
        if (!faller.waterDistress().escaping()) return landing();
        var larder = HullLarder.of(hat);
        if (dumped < ROOM && AquaticLife.freeSpot(hat, faller) == null && larder != null && !larder.portions().isEmpty()) return room(hat, larder);
        var grab = Grab.outOfWater(faller);
        if (grab.check(helper).ok()) return List.of(() -> act(new Face(SurfaceApproach.yaw(helper.position(), faller.position()))), () -> act(grab));
        var spot = AquaticLife.spot(hat, helper, false, hat.frame().toLocal(faller.position()));
        BooleanSupplier walk = () -> spot != null && ContactGeometry.reachesFrom(helper, hat.frame().toWorld(spot), faller)
                && spot.subtract(hat.frame().toLocal(helper.position())).horizontalDistance() >= .1;
        if (walk.getAsBoolean())
            return List.of(() -> act(new Walk(hat, spot)), () -> act(new Face(SurfaceApproach.yaw(helper.position(), faller.position()))));
        var water = Waters.cell(faller.level(), faller.position());
        if (water == null) return waiting("no_calm_water");
        if (!AquaticLife.stick(helper)) return waiting("no_stick");
        var route = Waters.route(hat, water);
        Waters.log(hat, helper, water, route, id());
        if (route.isEmpty()) return waiting("no_water_route");
        // Intermediate cells need a small arrival radius; the final leg ends at real reach, not at the brim's radius.
        return List.of(() -> act(route.size() > 3 ? new Paddle(Waters.leg(route), .35)
                : new Paddle(faller::position, remaining -> grab.check(helper).ok() || walk.getAsBoolean())));
    }

    private List<Supplier<Step>> waiting(String why) {
        return List.of(() -> act(new Await(() -> Grab.outOfWater(faller).check(helper).ok() || !faller.waterDistress().escaping(),
                WAIT, Behaviors.BOAT_CALL, "rescue_" + why)));
    }

    private List<Supplier<Step>> landing() {
        return List.of(() -> act(new Await(() -> faller.isInWaterOrBubble() || satisfied() || !faller.isAlive(),
                WAIT, Behaviors.BOAT_CALL, "rescue_faller_airborne")));
    }

    private void progress() {
        double now = helper.distanceTo(faller);
        if (distance - now >= .05) GameplayLog.limited(helper, "aquatic", "rescue.progress", 20,
                "rescue progress faller={} incident={} distance_before={} distance_after={} last={}",
                faller.getUUID(), incident, distance, now, helper.motor().lastDetail());
        distance = now;
    }

    /** No floor in the hollow for it: the portion that feeds least is tipped over the side first (a real, floating loss). */
    private List<Supplier<Step>> room(PlacedHatEntity hat, HullLarder larder) {
        var portion = larder.portion(hat, one -> true, helper.position(), true);
        return portion == null ? null : Provision.reach(helper, hat, portion.at(), new Pick(portion, one -> overboard(hat, one)));
    }

    /** The portion falls beyond the brim on the faller's side as a real floating item. */
    private boolean overboard(PlacedHatEntity hat, ItemStack one) {
        var out = faller.position().subtract(hat.position()).multiply(1, 0, 1);
        var dir = out.lengthSqr() < 1E-6 ? new Vec3(1, 0, 0) : out.normalize();
        var at = hat.position().add(dir.scale(HullAfloat.brimToward(hat, faller.position()) + .15)).add(0, .3, 0);
        var item = new ItemEntity(hat.level(), at.x, at.y, at.z, one.copy());
        item.setDeltaMovement(dir.scale(.08));
        item.setDefaultPickUpDelay();
        if (!hat.level().addFreshEntity(item)) return false;
        dumped++;
        GameplayLog.event(helper, "aquatic", "rescue made_room item={} dropped={} faller={} incident={}", one.getItem(), item.getUUID(), faller.getUUID(), incident);
        return true;
    }

    private List<Supplier<Step>> fromBank() {
        progress();
        if (!faller.isAlive()) return lost();
        if (faller.getVehicle() == helper) {
            var at = edge;
            if (at == null) return List.of(() -> act(new Release(faller)));
            // step back from the water before letting go, so it comes down on the dry ground
            var inland = at.stand(helper.getBbWidth()).subtract(at.normal().scale(helper.getBbWidth()));
            return List.of(() -> SurfaceApproach.arrived(helper, inland) ? null : act(Walk.stand(inland, 1)),
                    () -> act(new Face(at.yaw() + 180)), () -> act(new Release(faller)), Ferry.landed(faller));
        }
        if (!faller.waterDistress().escaping()) return landing();
        var grab = Grab.outOfWater(faller);
        if (grab.check(helper).ok()) return List.of(() -> act(new Face(SurfaceApproach.yaw(helper.position(), faller.position()))), () -> act(grab));
        if (edge == null || edge.edge().distanceTo(faller.position()) > 1.5) edge = Waters.edges(helper, faller.position(), 2, false).stream()
                .filter(e -> SurfaceApproach.arrived(helper, e.stand(helper.getBbWidth())) || SurfaceApproach.canApproach(helper, e.stand(helper.getBbWidth())))
                .findFirst().orElse(null);
        if (edge == null) return waiting("no_bank_edge");
        var stand = edge.stand(helper.getBbWidth());
        if (!SurfaceApproach.arrived(helper, stand)) return List.of(() -> act(Walk.stand(stand, 1.1)));
        return List.of(() -> act(grab));
    }

    @Override public void ended(IntentRunner.Result result) {
        if (faller.getVehicle() == helper && result != IntentRunner.Result.DONE) Release.now(helper, faller, true, "rescue_ended");
        if (result == IntentRunner.Result.DONE || result == IntentRunner.Result.LOST) AskLedger.server().ended(incident, helper.getUUID(), result.name());
        else if (result == IntentRunner.Result.EXHAUSTED && acted) {
            var owner = helper.motor().owner();
            if (helper.motor().refusal() == null && owner != null && owner.active())
                GameplayLog.limited(helper, "aquatic", "rescue.owner", WAIT,
                        "rescue unavailable faller={} incident={} owner={} counted=false", faller.getUUID(), incident, owner.ownerId());
            else failed.accept(incident);
        }
        GameplayLog.limited(helper, "aquatic", "rescue.end." + incident, result == IntentRunner.Result.EXHAUSTED ? WAIT : 0,
                "rescue end result={} faller={} incident={} faller_alive={} in_water={} at={} last={}", result, faller.getUUID(),
                incident, faller.isAlive(), faller.isInWaterOrBubble(), GameplayLog.at(faller.position()), helper.motor().lastDetail());
    }
}
