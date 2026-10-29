package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Lifting a young of hers out of the water (refactor WP6b; design 8.3 落水他救; AQUATIC 落水、救助与靠岸), the intent of a helper who
 * accepted the young's incident (spec 11.3: one decision per helper and incident, SOFT). The faller keeps working its own way out
 * until it is held; only the one who holds it moves it (a held body takes no second grab). Her ways:
 * <ul>
 *   <li>from her own hat afloat ({@code lift}): paddle near (with her stick; without one she waits for it to drift or swim near), walk
 *       on the hat to the side nearest it, take it with the mouth or the braid tip (Grab picks the hold that reaches), and put it
 *       down over her hollow, where it drops in. A hollow without floor for it gets room first: the portion that feeds least goes
 *       over the side, a real loss that floats and may be fished up again.</li>
 *   <li>from the bank ({@code from_bank}, ashore): only a faller within reach of an edge she can stand at: walk there, take it, step
 *       back from the water and put it down on the dry ground; she does not go into the water.</li>
 * </ul>
 * Each way is tried three times (a lift that slipped or drifted off is tried again from where things are then); a helper that has
 * tried them all, or gives up, lets go where it holds it and is done with the incident.
 */
final class Rescue implements Intent {
    /** The longest rescue (ticks), the longest wait for the faller to come within reach, and the tries of each way. */
    private static final int DEADLINE = 1200, WAIT = 60, TRIES = 3, ROOM = 2;

    private final YukkuriEntity helper, faller;
    private final String incident;
    private final long started;
    private Waters.Edge edge;
    /** Portions tipped over the side for its room in this rescue (at most {@link #ROOM}: more food is not what fills the hollow). */
    private int dumped;

    Rescue(YukkuriEntity helper, YukkuriEntity faller, String incident) {
        this.helper = helper;
        this.faller = faller;
        this.incident = incident;
        this.started = helper.level().getGameTime();
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
        var out = new ArrayList<Method>();
        for (int i = 1; i <= TRIES; i++) out.add(new Plan("lift_" + i, this::aboard, Set.of(Reason.NO_PROGRESS, Reason.NO_CONTACT, Reason.TOO_FAR, Reason.TARGET_MOVED), this::lift));
        for (int i = 1; i <= TRIES; i++) out.add(new Plan("from_bank_" + i, this::ashore, Set.of(Reason.NO_PROGRESS, Reason.NO_CONTACT, Reason.TARGET_MOVED), this::fromBank));
        return out;
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
        if (!faller.isAlive()) return lost();
        var hat = AquaticLife.riding(helper);
        if (hat == null) return null;
        if (faller.getVehicle() == helper) return Ferry.releaseAboard(helper, hat, faller);
        if (!faller.isInWaterOrBubble()) return null;
        var larder = HullLarder.of(hat);
        if (dumped < ROOM && AquaticLife.freeSpot(hat, faller) == null && larder != null && !larder.portions().isEmpty()) return room(hat, larder);
        var grab = new Grab(faller);
        if (grab.check(helper).ok()) return List.of(() -> act(new Face(SurfaceApproach.yaw(helper.position(), faller.position()))), () -> act(grab));
        var spot = AquaticLife.spot(hat, helper, false, hat.frame().toLocal(faller.position()));
        var here = hat.frame().toLocal(helper.position());
        if (spot != null && spot.subtract(here).horizontalDistance() >= .1)
            return List.of(() -> act(new Walk(hat, spot)), () -> act(new Face(SurfaceApproach.yaw(helper.position(), faller.position()))));
        // near: the faller just off the brim at its widest, where a walk to that side brings it within the mouth's reach
        double near = HatGeometry.brim()[1] * hat.unit() + faller.getBbWidth() / 2 + .05;
        var water = Waters.cell(faller.level(), faller.position());
        if (water != null && AquaticLife.stick(helper) && hat.position().subtract(faller.position()).horizontalDistance() > near) {
            var route = Waters.route(hat, water);
            Waters.log(hat, helper, water, route, id());
            if (!route.isEmpty()) return List.of(() -> act(new Paddle(route.size() > 1 ? Waters.leg(route) : faller.position(), near)));
        }
        return List.of(() -> new Step.Wait(() -> new Grab(faller).check(helper).ok() || !faller.isInWaterOrBubble(), WAIT, Behaviors.BOAT_CALL));
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
        var at = hat.position().add(dir.scale(HatGeometry.brim()[1] * hat.unit() + .15)).add(0, .3, 0);
        var item = new ItemEntity(hat.level(), at.x, at.y, at.z, one.copy());
        item.setDeltaMovement(dir.scale(.08));
        item.setDefaultPickUpDelay();
        if (!hat.level().addFreshEntity(item)) return false;
        dumped++;
        GameplayLog.event(helper, "aquatic", "rescue made_room item={} dropped={} faller={} incident={}", one.getItem(), item.getUUID(), faller.getUUID(), incident);
        return true;
    }

    private List<Supplier<Step>> fromBank() {
        if (!faller.isAlive()) return lost();
        if (faller.getVehicle() == helper) {
            var at = edge;
            if (at == null) return List.of(() -> act(new Release(faller)));
            // step back from the water before letting go, so it comes down on the dry ground
            var inland = at.stand(helper.getBbWidth()).subtract(at.normal().scale(helper.getBbWidth()));
            return List.of(() -> SurfaceApproach.arrived(helper, inland) ? null : act(Walk.stand(inland, 1)),
                    () -> act(new Face(at.yaw() + 180)), () -> act(new Release(faller)), Ferry.landed(faller));
        }
        if (!faller.isInWaterOrBubble()) return null;
        var grab = new Grab(faller);
        if (grab.check(helper).ok()) return List.of(() -> act(new Face(SurfaceApproach.yaw(helper.position(), faller.position()))), () -> act(grab));
        if (edge == null || edge.edge().distanceTo(faller.position()) > 1.5) edge = Waters.edges(helper, faller.position(), 2, false).stream()
                .filter(e -> SurfaceApproach.arrived(helper, e.stand(helper.getBbWidth())) || SurfaceApproach.canApproach(helper, e.stand(helper.getBbWidth())))
                .findFirst().orElse(null);
        if (edge == null) return null;
        var stand = edge.stand(helper.getBbWidth());
        if (!SurfaceApproach.arrived(helper, stand)) return List.of(() -> act(Walk.stand(stand, 1.1)));
        return List.of(() -> act(new Face(SurfaceApproach.yaw(helper.position(), faller.position()))),
                () -> new Step.Wait(() -> new Grab(faller).check(helper).ok() || !faller.isInWaterOrBubble(), WAIT, Behaviors.BOAT_CALL));
    }

    @Override public void ended(IntentRunner.Result result) {
        if (faller.getVehicle() == helper && result != IntentRunner.Result.DONE) Release.now(helper, faller, true, "rescue_ended");
        if (result != IntentRunner.Result.INTERRUPTED) AskLedger.server().ended(incident, helper.getUUID(), result.name());
        GameplayLog.event(helper, "aquatic", "rescue end result={} faller={} incident={} faller_alive={} in_water={} at={}", result, faller.getUUID(),
                incident, faller.isAlive(), faller.isInWaterOrBubble(), GameplayLog.at(faller.position()));
    }
}
