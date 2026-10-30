package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Drive;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A parent's care aboard (照料; CHILDHOOD_LIFECYCLE 新生喂糜, 帽船采用自身的移动、口部接触条件相同): on her
 * hat afloat she walks on its surface to a young of hers that needs her and does what the care on land does, by its rules:
 * <ul>
 *   <li>{@code feed_young}: a hungry young that cannot eat by itself, her care decision accepted: she takes a stored portion with her
 *       mouth as her care food (the feeding's own held portion: kept, saved and shown), chews it into mash for a young that needs
 *       mash, stands mouth to mouth with it and, their mouths meeting for the contact time, hands it into its meal. Done once
 *       handed; a young turned away or moved on ends the wait and the next try aims again.</li>
 *   <li>{@code comfort_young}: a young gasping after the water, or cold: she stands beside it, faces it and nuzzles it
 *       ({@code boat_nuzzle}).</li>
 * </ul>
 */
final class Care implements Intent {
    /** The longest feeding and comfort (ticks); how long a contact may take to come about; the nuzzle's length. */
    private static final int FEED = 900, COMFORT = 400, CONTACT = 160, NUZZLE = 120;

    private final YukkuriEntity parent, child;
    private final boolean feed;
    private final long started;
    private long chewEnd;
    private int contact;
    private boolean done;

    private Care(YukkuriEntity parent, YukkuriEntity child, boolean feed) {
        this.parent = parent;
        this.child = child;
        this.feed = feed;
        this.started = parent.level().getGameTime();
    }

    static Care feed(YukkuriEntity parent, YukkuriEntity child) { return new Care(parent, child, true); }
    static Care comfort(YukkuriEntity parent, YukkuriEntity child) { return new Care(parent, child, false); }

    /** This parent is feeding {@code young} now (her running intent), so the young turns to her ({@link Taking}). */
    static boolean feeding(YukkuriEntity parent, YukkuriEntity young) {
        return Drive.running(parent, AquaticGoal.DOMAIN) instanceof Care care && care.feed && care.child == young && !care.done;
    }

    /** Its care is due now: hungry, and unable to eat by itself (fed by her), or gasping after the water (comforted). */
    static boolean hungry(YukkuriEntity parent, YukkuriEntity child) {
        return child.isAlive() && parent.family().caresFor(child) && !child.family().canEatIndependently() && child.life().canReceiveCareFood()
                && child.life().nutrition() < parent.life().nutrition() - parent.species().traits().dote();
    }

    /** Gasping after the water, or cold: the young she comforts. */
    static boolean upset(YukkuriEntity child) {
        return child.isAlive() && (child.waterDistress().phase() == WaterDistress.Phase.RECOVER || child.life().temperature().cold());
    }

    @Override public String id() { return feed ? "feed_young" : "comfort_young"; }
    @Override public long deadline() { return started + (feed ? FEED : COMFORT); }
    @Override public boolean satisfied() { return done; }
    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.riding(parent);
        if (hat == null || !child.isAlive() || child.getVehicle() != hat) return null;
        contact = 0;
        if (!feed) return mouthToMouth(hat, Behaviors.BOAT_NUZZLE, NUZZLE + CONTACT, this::nuzzled);
        var held = parent.family().carrying();
        if (held.isEmpty()) {
            var larder = HullLarder.of(hat);
            var portion = larder == null ? null : larder.portion(hat, one -> child.life().canReceiveCareFood(one), parent.position(), false);
            return portion == null ? null : Provision.reach(parent, hat, portion.at(), new Pick(portion, parent.family()::holdCareFood));
        }
        if (child.family().needsMash() && !InfantFood.isMash(held)) {
            if (chewEnd == 0) chewEnd = parent.level().getGameTime() + FamilyLife.CHEW_TICKS;
            return List.of(() -> new Step.Wait(this::chewed, FamilyLife.CHEW_TICKS + 5, Behaviors.FAMILY_CHEW));
        }
        return mouthToMouth(hat, Behaviors.BOAT_FEED_YOUNG, CONTACT, this::handed);
    }

    /** A feeding that did not hand the food over puts the portion she took back into her hold, where there is floor for it. */
    @Override public void ended(IntentRunner.Result result) {
        if (!feed || done) return;
        var hat = AquaticLife.riding(parent);
        var larder = HullLarder.of(hat);
        var held = parent.family().carrying();
        if (larder != null && !held.isEmpty() && parent.family().putBackCareFood(one -> larder.keep(hat, one, parent)))
            GameplayLog.event(parent, "aquatic", "feed_young put back item={} child={} result={}", held.getItem(), child.getUUID(), result);
    }

    /** Chewing done: the held portion becomes mash (its form only). */
    private boolean chewed() {
        if (parent.level().getGameTime() < chewEnd) return false;
        parent.family().chewCareFood(child);
        chewEnd = 0;
        return true;
    }

    /** Their mouths have met for the contact time: the held portion passes into its meal. */
    private boolean handed() {
        if (!meet()) return false;
        done = parent.family().handCareFood(child);
        if (done) GameplayLog.event(parent, "aquatic", "feed_young handed child={} nutrition={}", child.getUUID(), Math.round(child.life().nutrition()));
        return done;
    }

    private boolean nuzzled() {
        if (!near()) { contact = 0; return false; }
        if (++contact == 10) { parent.showReaction("closed_smile", "nuzzle", 60); child.showReaction("happy", "caregiver", 60); }
        if (contact < NUZZLE) return false;
        GameplayLog.event(parent, "aquatic", "comfort_young done child={}", child.getUUID());
        return done = true;
    }

    /** Each tick of the wait: both look at each other; true once their mouths have met for the feeding's contact time. */
    private boolean meet() {
        parent.getLookControl().setLookAt(child, 30, 30);
        child.getLookControl().setLookAt(parent, 30, 30);
        contact = FamilyFeedingContact.mouthsMeet(parent, child) ? contact + 1 : 0;
        return contact >= FamilyFeedingContact.REQUIRED_CONTACT_TICKS;
    }

    private boolean near() {
        parent.getLookControl().setLookAt(child, 30, 30);
        return parent.position().subtract(child.position()).horizontalDistance() <= FamilyFeedingContact.mouthDistance(parent, child) + .1;
    }

    /** Walk to stand mouth to mouth with the young (the nearest of eight stands round it where her foot has room), face it, and wait. */
    private List<Supplier<Step>> mouthToMouth(PlacedHatEntity hat, String clip, int ticks, BooleanSupplier until) {
        return List.of(() -> {
                    var stand = stand(hat);
                    var here = hat.frame().toLocal(parent.position());
                    return stand == null || stand.subtract(here).horizontalDistance() < .1 ? null : new Step.Do(new Walk(hat, stand));
                },
                () -> new Step.Do(new Face(SurfaceApproach.yaw(parent.position(), child.position()))),
                () -> new Step.Wait(until, ticks, clip));
    }

    /**
     * The young's side of a feeding aboard ({@code be_fed}): while its parent offers it food it turns to her (its own Face) and keeps
     * still, turning again whenever it is off by more than a little. Done once it has the food in its meal, or she stops offering.
     */
    static final class Taking implements Intent {
        private static final int DEADLINE = 400;
        private static final float OFF = 20;
        private final YukkuriEntity young, parent;
        private final long started;

        Taking(YukkuriEntity young, YukkuriEntity parent) {
            this.young = young;
            this.parent = parent;
            this.started = young.level().getGameTime();
        }

        @Override public String id() { return "be_fed"; }
        @Override public long deadline() { return started + DEADLINE; }
        @Override public boolean satisfied() { return young.life().mealTicks() > 0 || !feeding(parent, young); }
        @Override public List<Method> methods() { return List.of(Plan.of("turn_to_parent", this::derive)); }

        private float yaw() { return SurfaceApproach.yaw(young.position(), parent.position()); }

        private List<Supplier<Step>> derive() {
            return List.of(() -> new Step.Do(new Face(yaw())), () -> new Step.Wait(() -> {
                young.getLookControl().setLookAt(parent, 30, 30);
                return satisfied() || Math.abs(Mth.wrapDegrees(yaw() - young.yBodyRot)) > OFF;
            }, DEADLINE, Behaviors.BOAT_FEED_YOUNG));
        }
    }

    /** The supported spot with room for her (hat units) at the feeding's standing distance from the young, nearest where she is. */
    private Vec3 stand(PlacedHatEntity hat) {
        var frame = hat.frame();
        var at = frame.toLocal(child.position());
        var here = frame.toLocal(parent.position());
        double d = FamilyFeedingContact.standingDistance(parent, child) / frame.unit();
        Vec3 best = null;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            var foot = new Vec3(at.x + Math.cos(a) * d, 0, at.z + Math.sin(a) * d);
            var height = hat.support(foot, Occupancy.foot(parent.getBbWidth()));
            if (height.isEmpty()) continue;
            var spot = new Vec3(foot.x, height.getAsDouble(), foot.z);
            if (hat.room(parent, spot) && (best == null || spot.distanceToSqr(here) < best.distanceToSqr(here))) best = spot;
        }
        return best;
    }
}
