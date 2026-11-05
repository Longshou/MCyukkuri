package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.family.CareApproach;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A parent's care aboard (照料; docs/FEATURES.md 子代生命周期与离家的新生受喂, 帽船采用自身的移动、口部接触条件相同): on her
 * hat afloat she takes stored food in place, then the young approaches for feeding or comfort, using the same contact as care on land:
 * <ul>
 *   <li>{@code feed_young}: a hungry young that cannot eat by itself, her care decision accepted: she takes a stored portion with her
 *       mouth as her care food (the feeding's own held portion: kept, saved and shown), chews it into mash for a young that needs
 *       mash, stands mouth to mouth with it and, their mouths meeting for the contact time, hands it into its meal. Done once
 *       handed; a young turned away or moved on ends the wait and the next try aims again.</li>
 *   <li>{@code comfort_young}: a young gasping after the water, or cold: it approaches her and she nuzzles it
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

    /** A frightened brim rider needs her braid only when the cabin is full and her head has real room; so does one that cannot play there. */
    static boolean needsShelter(YukkuriEntity child, PlacedHatEntity hat) {
        if (child.family().stage() != FamilyLife.Stage.BABY || !PlayAboard.onBrim(child, hat)) return false;
        boolean cabin = !hat.places(child, SeatTarget.Kind.CABIN, 1).isEmpty();
        // One that cannot play on the brim and has no cabin move of its own (cannot walk or hop in) is lifted into the free cabin place.
        if (!child.childhood().canExercise()) return cabin && TakeSeat.offer(child, hat) == null;
        return !PlayAboard.fear(child, hat).equals("none") && !cabin && !hat.places(child, SeatTarget.Kind.HEAD, 1).isEmpty();
    }

    /** Ordinary parental placement: lift a brim child to shelter, or lower a head rider onto supported ground. */
    static final class Transfer implements Intent {
        private final YukkuriEntity parent, child;
        private final PlacedHatEntity hat;
        private final Release release;
        private final long started;

        Transfer(YukkuriEntity parent, YukkuriEntity child, PlacedHatEntity hat) {
            this.parent = parent;
            this.child = child;
            this.hat = hat;
            release = new Release(child, hat);
            started = parent.level().getGameTime();
        }

        @Override public String id() { return hat == null ? "set_down_young" : "shelter_young"; }
        @Override public long deadline() { return started + COMFORT; }
        @Override public Commitment commitment() { return Commitment.HARD; }
        /** Set down somewhere better than the brim it was lifted from; a lift that puts it back there fails and is not offered again
         *  until the boat's cell changes. */
        @Override public boolean satisfied() { return release.released() && (hat == null || !PlayAboard.onBrim(child, hat)); }
        @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }
        private List<Supplier<Step>> derive() {
            if (hat != null && child.getVehicle() != parent) return BraidFetch.lift(parent, child, hat);
            return List.of(() -> new Step.Do(release));
        }
        @Override public void ended(IntentRunner.Result result) {
            if (child.getVehicle() == parent && ParentCarry.hold(parent, child) != ParentCarry.Hold.HEAD)
                parent.family().carry().finish(child, id() + "_ended");
            GameplayLog.event(parent, "aquatic", "care transfer end kind={} child={} result={} released={} ground={} head={}",
                    id(), child.getUUID(), result, release.released(), SurfaceApproach.supportedGround(child, child.position()),
                    child.getVehicle() == parent && ParentCarry.hold(parent, child) == ParentCarry.Hold.HEAD);
        }
    }

    /** Called by the care owner's goal; the receiver's own goal performs its approach. */
    CareApproach.Result tick() {
        return parent.careApproach().tick(child, CareApproach.Kind.FEED);
    }

    /** Its care is due now: hungry, and unable to eat by itself (fed by her), or gasping after the water (comforted). */
    static boolean hungry(YukkuriEntity parent, YukkuriEntity child) {
        return child.isAlive() && parent.family().caresFor(child) && !child.family().canEatIndependently() && child.life().canReceiveCareFood()
                && parent.careApproach().mayTry(child)
                && child.life().nutrition() < parent.life().nutrition() - parent.species().traits().dote();
    }

    /** Gasping after the water (or its incident closed a moment ago, so a lift during the breath does not cancel the comfort), or cold:
     *  the young she comforts. */
    static boolean upset(YukkuriEntity child) {
        return child.isAlive() && (child.waterDistress().phase() == WaterDistress.Phase.RECOVER
                || child.waterDistress().closedWithin((long) PlayAboard.number("comfort_again")) || child.life().temperature().cold());
    }

    @Override public String id() { return feed ? "feed_young" : "comfort_young"; }
    @Override public long deadline() { return started + (feed ? FEED : COMFORT); }
    @Override public boolean satisfied() { return done; }
    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.riding(parent);
        if (hat == null || !child.isAlive() || child.getVehicle() != hat) return null;
        contact = 0;
        if (!feed) return mouthToMouth(Behaviors.BOAT_NUZZLE, NUZZLE + CONTACT, this::nuzzled);
        var held = parent.family().carrying();
        if (held.isEmpty()) {
            var larder = HullLarder.of(hat);
            var portion = larder == null ? null : larder.source(hat, parent, one -> child.life().canReceiveCareFood(one), "feed");
            return portion == null ? null : Provision.reach(new Pick(portion, parent.family()::holdCareFood));
        }
        if (child.family().needsMash() && !InfantFood.isMash(held)) {
            if (chewEnd == 0) chewEnd = parent.level().getGameTime() + FamilyLife.CHEW_TICKS;
            return List.of(() -> new Step.Wait(this::chewed, FamilyLife.CHEW_TICKS + 5, Behaviors.FAMILY_CHEW));
        }
        return mouthToMouth(Behaviors.BOAT_FEED_YOUNG, CONTACT, this::handed);
    }

    /** A feeding that did not hand the food over puts the portion she took back into her hold, if the stores have capacity. */
    @Override public void ended(IntentRunner.Result result) {
        parent.careApproach().cancel();
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
        if (!FamilyFeedingContact.mouthsMeet(parent, child)) { contact = 0; return false; }
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

    /** Contact is observed by the bounded wait; approach belongs to the two actors' goals. */
    private List<Supplier<Step>> mouthToMouth(String clip, int ticks, BooleanSupplier until) {
        return List.of(() -> new Step.Wait(until, ticks, clip));
    }
}
