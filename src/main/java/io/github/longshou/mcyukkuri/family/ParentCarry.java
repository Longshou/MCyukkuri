package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.BodyDriver;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.ContactGeometry.Reach;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A parent carries a dependent baby over land as a real passenger, held the way the shared Grab took it (spec 9.4): in the
 * mouth (one; Reimu several within a volume budget), and a Marisa one more baby at her braid tip. The walk home is the
 * shared Walk and the put-down the shared Release, at the child's own position for a real reason (never copied, never
 * teleported to a goal). Client code reads only the synced riders and braid slot through {@link #rawSeat} /
 * {@link #mouthLane}; the drawn pose follows the live carrier model in client ParentCarryPresentation.
 */
public final class ParentCarry {
    public enum Purpose { MIGRATE, RETRIEVE, NEST_RIM }
    public enum Step { WORKING, DONE, FAILED }
    /** Pause before the same child is picked up again after an interruption; feeding gets longer. */
    static final int HOLD_TICKS = 100, FEED_HOLD_TICKS = 200;
    /** After the carrier has settled at the destination the child is kept at most this long while it inherits the home. */
    static final int ARRIVE_GRACE_TICKS = 600;
    private static final int MAX_CARRY_TICKS = 12000;
    /** One mouth or braid hold takes a child up to this share of the carrier's volume, scaled by strength (project design). */
    static final double HOLD_FRACTION = .20;
    /** Reimu holds several babies in her mouth (project design): their summed volume stays within this fraction of her own, scaled by strength. */
    static final double MOUTH_LOAD_FRACTION = .45;
    static final int MAX_MOUTH_BABIES = 4;
    /**
     * Mouth lanes (project design): children hang side by side across the lips in boarding order, each gripped at the
     * top-rear of its head; the closed lips stretch a little for each extra child, and a child whose lane lies past them is
     * held by its inner side lock instead (lower on the head, so it rides a little higher and fans outward). Carrier model units.
     */
    static final double LANE_GAP = .12, MOUTH_HALF = 1.6, LIP_STRETCH = .25, GRIP_SIDE = 5.2, GRIP_DROP = 3.2, LANE_FAN = 12;
    /** The grip on a child's head (child model units): top-rear of the head, the scruff; the braid tip closes there too. */
    public static final double GRIP_Y = 8.2, GRIP_Z = 4.0;
    /** The bite sits this far in front of the lip plane (carrier model units), so the hanging back clears the face; lanes
     *  out at the cheeks come forward a little more (per squared lane offset) to clear the side locks. */
    public static final double LIP_GAP = .8, LANE_FORWARD = .035;
    /** Forward offset of a lane's bite from the lip plane (carrier model units). */
    public static double biteForward(MouthLane lane) { return LIP_GAP + LANE_FORWARD * lane.side() * lane.side(); }
    /** The lowest a held child's feet hang above the carrier's feet (carrier model units). */
    public static final double MOUTH_FLOOR = .2;
    private record Hold(long until, String reason) {}

    private final YukkuriEntity carrier;
    private final Map<UUID, Hold> holds = new HashMap<>();
    private Purpose purpose;
    private long since, arrivedAt;
    private int homeFailures, walkFailures;
    private UUID lifting;
    /** Where the carry's own Walk was last sent. */
    private Vec3 walkTo;
    /** The carry as the motor owner: active while a lift runs, or while the family carry task walks the child home. */
    private final MotorOwner owner = new MotorOwner() {
        @Override public String ownerId() { return "family_carry"; }
        @Override public boolean active() { return lifting != null || purpose != null && carrier.family().task() == FamilyLife.Task.CARRY; }
    };
    /** NEST_RIM: the nest whose rim the child lay on, and the free cushion spot being walked to. */
    private BlockPos rimNest;
    private Vec3 rimSpot;

    ParentCarry(YukkuriEntity carrier) { this.carrier = carrier; }

    // ------------------------------------------------------------------------------------------ shared geometry

    /** How this carrier holds this rider: at the braid tip when its synced braid slot names it, else in the mouth. */
    public static Reach reach(YukkuriView carrier, YukkuriView child) {
        return BodyDriver.braidBody(carrier.braidHold()) == child.getId() ? Reach.BRAID : Reach.MOUTH;
    }
    /** The rider at this carrier's braid tip, or null. */
    public static YukkuriView braidRider(YukkuriView carrier) {
        for (var rider : carrier.passengerViews()) if (reach(carrier, rider) == Reach.BRAID) return rider;
        return null;
    }
    /** The riders held in the mouth, in boarding order. */
    static List<YukkuriView> mouthRiders(YukkuriView carrier) {
        var out = new ArrayList<YukkuriView>();
        for (var rider : carrier.passengerViews()) if (reach(carrier, rider) == Reach.MOUTH) out.add(rider);
        return out;
    }
    public static boolean riding(YukkuriEntity carrier, Entity child) { return child instanceof YukkuriEntity && carrier.hasPassenger(child); }
    private static double size(YukkuriView v) { return v.growthScale() * v.getScale(); }
    /** Reimu carries babies in her mouth, several at once; every other mouth carrier holds one. */
    public static boolean multi(YukkuriView carrier) { return carrier.getVariant() == YukkuriVariant.REIMU; }

    /** Feet of the carried child before any collision adjustment: under its mouth lane's grip, or at the braid tip, just off the ground. */
    public static Vec3 rawSeat(YukkuriView carrier, YukkuriView child, float partial) {
        float scale = (float) size(carrier);
        float yaw = carrier.level().isClientSide ? Mth.rotLerp(partial, carrier.previousBodyYaw(), carrier.bodyYaw()) : carrier.bodyYaw();
        var base = carrier.level().isClientSide ? carrier.getPosition(partial) : carrier.position();
        Vector3f feet;
        if (reach(carrier, child) == Reach.BRAID) {
            // The braid tip's rest spot beside the body; the client bends the braid down to the child's scruff.
            var tip = ContactGeometry.braidTipModel();
            feet = new Vector3f((float) tip.x, (float) MOUTH_FLOOR, (float) tip.z);
        } else {
            // Feet under the lane's grip at rest: the client hangs the child from the live lip (ParentCarryPresentation).
            var lane = mouthLane(carrier, child);
            var mouth = MouthPlacement.anchor(carrier);
            double rho = size(child) / Math.max(1e-6, size(carrier));
            // The anchor lies about .35 in front of the lip plane the client grips at.
            feet = new Vector3f((float) lane.side(), (float) Math.max(MOUTH_FLOOR, mouth.y - lane.gripY() * rho),
                    (float) (mouth.z + .35 - biteForward(lane) - lane.gripZ() * rho));
        }
        feet.mul(scale / 16).rotateY((180 - yaw) * Mth.DEG_TO_RAD);
        return base.add(feet.x, feet.y, feet.z);
    }
    /** Where one mouth-held child hangs: lane centre across the lips, grip on its head, outward fan and side tilt (degrees). */
    public record MouthLane(double side, double gripX, double gripY, double gripZ, float fan, float roll) {}
    /** The lane of this child among the carrier's mouth-held riders (boarding order; a child not yet aboard comes last). */
    public static MouthLane mouthLane(YukkuriView carrier, YukkuriView child) {
        var riders = mouthRiders(carrier);
        if (riders.stream().noneMatch(rider -> rider.getUUID().equals(child.getUUID()))) riders.add(child);
        int n = riders.size(), me = 0;
        double carrierSize = Math.max(1e-6, size(carrier)), width = (n - 1) * LANE_GAP;
        double[] half = new double[n];
        for (int i = 0; i < n; i++) {
            half[i] = HatGeometry.formHalfWidth(riders.get(i).assetId()) * size(riders.get(i)) / carrierSize;
            width += half[i] * 2;
            if (riders.get(i).getUUID().equals(child.getUUID())) me = i;
        }
        double x = -width / 2, side = 0;
        for (int i = 0; i <= me; i++) { side = x + half[i]; x += half[i] * 2 + LANE_GAP; }
        double rho = size(child) / carrierSize, lips = MOUTH_HALF * (1 + LIP_STRETCH * Math.max(0, n - 1));
        double grip = Mth.clamp((Mth.clamp(side, -lips, lips) - side) / Math.max(1e-6, rho), -GRIP_SIDE, GRIP_SIDE), edge = grip / GRIP_SIDE;
        float fan = n < 2 ? 0 : (float) (side / Math.max(1e-6, width / 2) * LANE_FAN);
        return new MouthLane(side, grip, GRIP_Y - GRIP_DROP * edge * edge, GRIP_Z - .8 * edge * edge, fan, (float) (edge * 3));
    }
    /** Mouth carry closes the carrier's mouth on the child. */
    public static boolean mouthCarrying(YukkuriView carrier) { return carrier.isVehicle() && !mouthRiders(carrier).isEmpty(); }

    /** Seat used by the passenger position update: the raw seat pulled toward the carrier's own cell until the child fits. */
    public static Vec3 seat(YukkuriEntity carrier, YukkuriEntity child) {
        // The client has already moved the carrier this tick: use its current pose (partial 1) so the child does not trail it.
        var raw = rawSeat(carrier, child, 1);
        for (double t : new double[]{0, .35, .7, 1}) {
            var at = new Vec3(Mth.lerp(t, raw.x, carrier.getX()), raw.y, Mth.lerp(t, raw.z, carrier.getZ()));
            var box = child.getDimensions(child.getPose()).makeBoundingBox(at).deflate(.02);
            if (!carrier.level().getBlockCollisions(child, box).iterator().hasNext() && !carrier.level().containsAnyLiquid(box)) return at;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------ capacity and rules

    /** Dependents that cannot keep up on their own: babies, and young crawlers (hurt foot, not yet able to jump). */
    public static boolean needsCarry(YukkuriEntity child) {
        return child.isAlive() && !child.family().adult() && !child.family().awaitingLanding() && child.childhood().shouldStayWithParents()
                && (child.family().stage() == FamilyLife.Stage.BABY || child.canCrawlNow());
    }
    private static double volume(YukkuriView e) { return e.getBbWidth() * e.getBbWidth() * e.getBbHeight(); }
    private static double strength(YukkuriEntity carrier) {
        return Math.clamp((carrier.life().energy() - 8) / 60.0, .2, 1) * Math.clamp(carrier.species().traits().might(), .8, 1.5);
    }
    private static boolean mouthFree(YukkuriEntity carrier) {
        return carrier.getMouthItem().isEmpty() && carrier.getMouthBodyFood().isEmpty() && carrier.habitat().carrying().isEmpty();
    }
    /**
     * Why this carrier could not hold this child with {@code reach} (size, form, stage, a taken hold); null when it can. The
     * braid is a Marisa's and takes one baby; the mouth takes one, a Reimu several within her volume budget. For a rider
     * already held ({@code ongoing}) only the riders ahead of it in the same hold count.
     */
    public static String holdProblem(YukkuriEntity carrier, YukkuriEntity child, Reach reach, boolean ongoing) {
        if (carrier == child) return "self";
        if (!carrier.isAlive() || !child.isAlive()) return "dead";
        if (carrier.level() != child.level()) return "level";
        if (!carrier.family().adult() || child.family().adult()) return "stage";
        if (carrier.profile().houseBody() || carrier.profile().dangerToYukkuri()) return "carrier_kind";
        if (carrier.isPassenger()) return "carrier_boarded";
        if (child.isVehicle()) return "child_carrying";
        // A body standing on a host (the placed catch hat) is lifted straight off it (spec 5.2 OnHost -> Held).
        if (child.isPassenger() && child.getVehicle() != carrier && !(child.getVehicle() instanceof SurfaceHost)) return "child_boarded";
        if (carrier.livePregnancyProgress() > 0) return "pregnant";
        if (volume(child) / Math.max(1e-6, volume(carrier)) > HOLD_FRACTION * strength(carrier)) return "too_big";
        if (reach == Reach.BRAID) {
            if (!ContactGeometry.braided(carrier)) return "no_braid";
            if (child.family().stage() != FamilyLife.Stage.BABY) return "not_baby";
            var held = braidRider(carrier);
            return held != null && held != child ? "occupied" : null;
        }
        if (!mouthFree(carrier)) return "mouth_full";
        // Babies already in the mouth (for a rider: those ahead of it) share it.
        double load = volume(child);
        int count = 1;
        for (var rider : mouthRiders(carrier)) {
            if (rider == child) { if (ongoing) break; continue; }
            load += volume(rider);
            count++;
        }
        if (!multi(carrier)) return count > 1 ? "occupied" : null;
        if (count > MAX_MOUTH_BABIES) return "occupied";
        return load > MOUTH_LOAD_FRACTION * strength(carrier) * volume(carrier) ? "mouth_load" : null;
    }
    /** Why neither hold could take this child now (the mouth's reason, the braid's beside it for a Marisa); null when one can. */
    public static String capacityProblem(YukkuriEntity carrier, YukkuriEntity child) {
        var mouth = holdProblem(carrier, child, Reach.MOUTH, false);
        if (mouth == null) return null;
        var braid = holdProblem(carrier, child, Reach.BRAID, false);
        return braid == null ? null : !ContactGeometry.braided(carrier) || braid.equals(mouth) ? mouth : mouth + "/" + braid;
    }
    /** Vanilla seat acceptance for forced and unforced boarding (YukkuriEntity.canAddPassenger). */
    public static boolean canSeat(YukkuriEntity carrier, Entity passenger) {
        return passenger instanceof YukkuriEntity child && capacityProblem(carrier, child) == null && !carrier.isInWaterOrBubble()
                && !child.isInWaterOrBubble() && !carrier.operations().active() && !child.operations().active();
    }

    /** The carrier is not busy with something that needs its body, mouth or attention right now. */
    private boolean ready() {
        var life = carrier.life();
        return FamilyLife.ordinaryLandFree(carrier) && !carrier.isPanicking() && !carrier.isOnFire() && !life.isSleeping()
                && life.mealTicks() == 0 && (!life.hasUrgentNeed() || carrier.settlement().marchTiredOnly());
    }
    /** Structural ability only (size, form, a free hold); the child waits for such a carrier instead of walking off. */
    public boolean capable(YukkuriEntity child) { return capacityProblem(carrier, child) == null; }

    /** A dependent baby waits for a parent who can carry it while the family travels, instead of crawling after it. */
    public static boolean awaitsCarrier(YukkuriEntity child) {
        if (!needsCarry(child) || !child.family().familyTravelling() || !(child.level() instanceof ServerLevel level)) return false;
        for (var id : new UUID[]{child.family().mother(), child.family().father()})
            if (id != null && level.getEntity(id) instanceof YukkuriEntity parent && parent.isAlive() && parent.family().adult()
                    && parent.settlement().familyMarching() && child.life().canRecognize(parent) && parent.life().canRecognize(child)
                    && (parent.family().carry().capable(child) || parent.hasPassenger(child)))
                return true;
        return false;
    }
    /** Migration escorts must be able to carry a child that cannot walk with them. */
    public static boolean mayEscort(YukkuriEntity parent, YukkuriEntity child) {
        return !needsCarry(child) || parent.family().carry().capable(child) || parent.hasPassenger(child);
    }
    /** Escort preference for a baby that needs carrying: how many more babies like it this parent can still take (0: none). */
    public static int carryRank(YukkuriEntity parent, YukkuriEntity child) {
        return needsCarry(child) ? parent.family().carry().room(child) : 0;
    }
    /**
     * A mouth-carrier with babies to carry keeps the mouth for them and packs no food: the mother always does, unless her
     * braid alone takes them; the father only when the mother cannot take them all (project design, so one parent still
     * brings the rations).
     */
    public static boolean reservesMouth(YukkuriEntity parent) {
        if (!parent.family().adult() || !(parent.level() instanceof ServerLevel level)) return false;
        var kids = level.getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(24),
                c -> c != parent && needsCarry(c) && Bonds.parentOf(parent, c));
        if (kids.size() <= parent.family().carry().braidSeats(kids)) return false;
        if (kids.stream().anyMatch(c -> parent.getUUID().equals(c.family().mother()))) return true;
        var first = kids.get(0);
        var mother = first.family().mother() != null && level.getEntity(first.family().mother()) instanceof YukkuriEntity m && m.isAlive()
                && m.family().adult() ? m : null;
        return kids.size() > (mother == null ? 0 : mother.family().carry().seatsFor(kids));
    }

    /** Whether this carrier holds {@code kid} with {@code reach} already, or could take it there from now. */
    private boolean fits(YukkuriEntity kid, Reach reach) {
        return carrier.hasPassenger(kid) ? reach(carrier, kid) == reach : holdProblem(carrier, kid, reach, false) == null;
    }
    /** Babies like {@code child} this carrier could take from now on, this one included: 0 if it cannot take it. */
    int room(YukkuriEntity child) {
        int braid = holdProblem(carrier, child, Reach.BRAID, false) == null ? 1 : 0;
        if (holdProblem(carrier, child, Reach.MOUTH, false) != null) return braid;
        if (!multi(carrier)) return 1 + braid;
        double load = 0;
        var riders = mouthRiders(carrier);
        for (var rider : riders) load += volume(rider);
        int byVolume = (int) Math.floor((MOUTH_LOAD_FRACTION * strength(carrier) * volume(carrier) - load) / Math.max(1e-6, volume(child)));
        return Math.max(1, Math.min(byVolume, MAX_MOUTH_BABIES - riders.size())) + braid;
    }
    /** How many of these babies the braid alone could hold (one, for a Marisa who has a baby it fits). */
    int braidSeats(List<YukkuriEntity> kids) { return kids.stream().anyMatch(kid -> fits(kid, Reach.BRAID)) ? 1 : 0; }

    /** How many of these babies this carrier could hold at once from empty (father's share of carrying is what is left). */
    int seatsFor(List<YukkuriEntity> kids) {
        if (!multi(carrier)) return Math.min(kids.size(), braidSeats(kids) + (kids.stream().anyMatch(kid -> fits(kid, Reach.MOUTH)) ? 1 : 0));
        double budget = MOUTH_LOAD_FRACTION * strength(carrier) * volume(carrier), load = 0;
        int count = 0;
        for (var kid : kids) {
            if (count >= MAX_MOUTH_BABIES || !fits(kid, Reach.MOUTH) || load + volume(kid) > budget) continue;
            load += volume(kid);
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------ pick-up (server)

    /** The first rider (the one a retrieval walk and the readout follow). */
    public YukkuriEntity carried() { return carrier.getFirstPassenger() instanceof YukkuriEntity y ? y : null; }
    public boolean carrying() { return carried() != null; }
    public Purpose purpose() { return purpose; }
    /** Every yukkuri riding this carrier (more than one for a Reimu's mouth or a Marisa's mouth and braid). */
    public List<YukkuriEntity> riders() {
        var out = new ArrayList<YukkuriEntity>();
        for (var rider : carrier.getPassengers()) if (rider instanceof YukkuriEntity y) out.add(y);
        return out;
    }
    /** The mouth is taken by a child, so mash feeding and food packing cannot use it. */
    public boolean mouthBusy() { return mouthCarrying(carrier); }

    private boolean blocked(YukkuriEntity child) {
        var hold = holds.get(child.getUUID());
        if (hold == null) return false;
        long now = carrier.level().getGameTime();
        if (hold.reason().equals("feeding")
                && (child.life().nutrition() >= 40 || carrier.family().task() == FamilyLife.Task.FEED_CHILD && carrier.family().target() == child)) {
            if (child.life().nutrition() >= 40) { holds.remove(child.getUUID()); return false; }
            return true;
        }
        if (now >= hold.until()) { holds.remove(child.getUUID()); return false; }
        return true;
    }

    /** Why this child should be carried now: during a family march, or home after straying out of the nest. */
    Purpose purposeFor(YukkuriEntity child) {
        if (!needsCarry(child) || child.isPassenger() || blocked(child)) return null;
        if (carrier.settlement().familyMarching() && child.family().familyTravelling()) return Purpose.MIGRATE;
        if (FamilyNest.rimNest(child) != null) return Purpose.NEST_RIM;
        var home = child.family().carryHome();
        if (home != null && child.distanceToSqr(Vec3.atBottomCenterOf(home)) > 4 * 4) return Purpose.RETRIEVE;
        return null;
    }
    /** A search for this child should end in a lift rather than in waiting for it to walk over. */
    boolean pickupWanted(YukkuriEntity child) { return (purposeFor(child) != null || child.getUUID().equals(lifting)) && capable(child); }
    /** During a march a baby near the parent still has to be lifted before the parent walks on. */
    boolean pickupDue(YukkuriEntity child) {
        var why = purposeFor(child);
        return (why == Purpose.MIGRATE || why == Purpose.NEST_RIM) && capable(child) && ready();
    }

    /** Standing over a child and counting down its lift (shown as the lean-down of carry_lift, or the braid's reach). */
    boolean lifting() { return lifting != null; }
    /** One tick of walking to the child, standing over it and lifting it with the shared Grab (mouth or braid). */
    Step tickPickup(YukkuriEntity child, int taskTicks) {
        var intent = purposeFor(child);
        if (child.getUUID().equals(lifting)) {
            // This carrier's own Grab is the pickup: still lifting, or finished with an outcome to read here.
            if (carrier.motor().current() instanceof Grab) return Step.WORKING;
            lifting = null;
            return grabOutcome(child);
        }
        var problem = capacityProblem(carrier, child);
        boolean ready = ready();
        if (problem != null || !ready) {
            GameplayLog.limited(carrier, "family", "carry.refused", 400, "carry pickup refused child={} reason={} ready={}", child.getUUID(),
                    problem == null ? "carrier_busy" : problem, ready);
            return Step.FAILED;
        }
        var grab = new Grab(child);
        var feasibility = grab.check(carrier);
        if (feasibility.reason() == Reason.NO_CONTACT) {
            // Neither mouth nor braid reaches yet: the stand beside the child (the braid turns its side to it on the lift).
            var stand = SurfaceApproach.beside(carrier, child, (carrier.getBbWidth() + child.getBbWidth()) * .5 + .15, 1.2);
            if (stand == null) {
                GameplayLog.limited(carrier, "family", "carry.approach", 200, "carry pickup refused child={} reason=no_supported_contact_stand", child.getUUID());
                return Step.FAILED;
            }
            SurfaceApproach.walkAround(carrier, stand, child, 1.1);
            return Step.WORKING;
        }
        carrier.getNavigation().stop();
        carrier.getLookControl().setLookAt(child, 30, 30);
        if (child.life().mealTicks() > 0 || child.isPanicking() || child.isInWaterOrBubble() || child.operations().active()) return Step.WORKING;
        if (!feasibility.ok()) {
            GameplayLog.limited(carrier, "family", "carry.grab", 100, "carry grab refused child={} reason={} {}",
                    child.getUUID(), feasibility.reason(), feasibility.detail());
            return Step.WORKING;
        }
        lifting = child.getUUID();
        // The lift commits the carry: the purpose and its clock start with the Grab, so the passive tick cannot re-adopt it.
        if (purpose == null) begin(intent, child);
        GameplayLog.event(carrier, "family", "carry lift started reach={} purpose={} child={} childPos={} carrierPos={} nutrition={}",
                name(grab.reach()), intent, child.getUUID(), child.blockPosition().toShortString(),
                carrier.blockPosition().toShortString(), child.life().nutrition());
        // A lift another owner's action refuses this tick is retried on the next one; the task budget bounds it.
        if (!carrier.motor().run(owner, grab)) { lifting = null; }
        return Step.WORKING;
    }

    /** The lift commits the carry: the purpose and its clock start when the Grab starts, so the passive tick cannot re-adopt it. */
    private void begin(Purpose intent, YukkuriEntity child) {
        purpose = intent == null ? Purpose.RETRIEVE : intent;
        since = carrier.level().getGameTime();
        arrivedAt = 0;
        homeFailures = walkFailures = 0;
        walkTo = rimSpot = null;
        var rim = purpose == Purpose.NEST_RIM ? FamilyNest.rimNest(child) : null;
        rimNest = rim == null ? null : rim.getBlockPos();
    }

    /** The outcome of this carrier's own Grab: a moved or slipped target is approached again, anything else ends the task. */
    private Step grabOutcome(YukkuriEntity child) {
        var outcome = carrier.motor().last();
        if (outcome instanceof Outcome.Done && carrier.hasPassenger(child)) return aboard(child) ? Step.DONE : Step.FAILED;
        var reason = outcome == null ? null : outcome.reason();
        if (reason == Reason.NO_CONTACT || reason == Reason.TARGET_MOVED) {
            GameplayLog.limited(carrier, "family", "carry.grab.retry", 40, "carry grab retry child={} reason={} shortfall={}",
                    child.getUUID(), reason, outcome.shortfall());
            return Step.WORKING;
        }
        GameplayLog.event(carrier, "family", "carry grab failed child={} outcome={} reason={}", child.getUUID(),
                outcome == null ? "none" : outcome.label(), reason);
        return Step.FAILED;
    }

    /** Called once Grab has mounted the child: the child's own tasks stop and the carry bookkeeping starts. */
    private boolean aboard(YukkuriEntity child) {
        child.life().interrupt();
        child.family().stop();
        child.social().stop();
        child.dailyLife().stop();
        child.getNavigation().stop();
        carrier.setPersistenceRequired();
        child.setPersistenceRequired();
        child.fallDistance = 0;
        lifting = null;
        // The purpose already started with the Grab; only a carry that lost it meanwhile begins it here.
        if (purpose == null) begin(null, child);
        holds.remove(child.getUUID());
        GameplayLog.event(carrier, "family", "carry pickup reach={} purpose={} child={} childStage={} seat={} nutrition={} volume={}/{} aboard={}",
                name(reach(carrier, child)), purpose, child.getUUID(), child.family().stage(), child.position(), child.life().nutrition(),
                String.format(Locale.ROOT, "%.3f", volume(child)), String.format(Locale.ROOT, "%.3f", volume(carrier)), carrier.getPassengers().size());
        carrier.showReaction("happy", "gentle", 40);
        return true;
    }
    private static String name(Reach reach) { return reach.name().toLowerCase(Locale.ROOT); }

    // ------------------------------------------------------------------------------------------ carrying (server)

    /** Per tick on the carrier: the one place that decides whether each child still rides. */
    void tick() {
        var all = riders();
        if (all.isEmpty()) {
            if (purpose != null && lifting == null) purpose = null;
            return;
        }
        long now = carrier.level().getGameTime();
        if (purpose == null) adopt(all.get(0), now);
        String why = carrierReason(now);
        if (why != null) { putDown(why, HOLD_TICKS); return; }
        for (var child : all) {
            // A pickup whose Grab has just mounted waits for its outcome on the next decision; do not judge its hold yet.
            if (child.getUUID().equals(lifting)) continue;
            String childWhy = childReason(child, now);
            if (childWhy != null) putDown(child, childWhy, childWhy.equals("feeding") ? FEED_HOLD_TICKS : HOLD_TICKS);
        }
        if (carrying() && (purpose == Purpose.RETRIEVE || purpose == Purpose.NEST_RIM)) carrier.family().beginCarry();
    }

    /** A passenger found without live state (reloaded save, another pick-up such as the hat unload): carry on with the rule that applies now. */
    private void adopt(YukkuriEntity child, long now) {
        purpose = carrier.settlement().familyMarching() ? Purpose.MIGRATE : Purpose.RETRIEVE;
        since = now;
        arrivedAt = 0;
        homeFailures = walkFailures = 0;
        walkTo = null;
        GameplayLog.event(carrier, "family", "carry resumed reach={} purpose={} child={}", name(reach(carrier, child)), purpose, child.getUUID());
        if (purpose == Purpose.RETRIEVE) carrier.family().beginCarry();
    }

    /** Reasons that end the whole carry (the carrier's own state, or the shared destination rule). */
    private String carrierReason(long now) {
        if (!carrier.isAlive()) return "carrier_died";
        if (carrier.isInWaterOrBubble()) return "water";
        var life = carrier.life();
        if (carrier.isOnFire()) return "carrier_burning";
        if (carrier.isPanicking()) return "carrier_panic";
        if (life.isSleeping()) return "carrier_sleeping";
        if (life.mealTicks() > 0 && mouthBusy()) return "carrier_eating";
        if (life.hasUrgentNeed() && !carrier.settlement().marchTiredOnly()) return "carrier_need";
        if (carrier.operations().active()) return "operation";
        if (now - since > MAX_CARRY_TICKS) return "timeout";
        if (purpose == Purpose.MIGRATE) {
            boolean landed = carrier.settlement().migrationLanded();
            if (landed && arrivedAt == 0) {
                arrivedAt = now;
                GameplayLog.event(carrier, "family", "carry destination reached aboard={}", carrier.getPassengers().size());
            }
            if (landed) {
                // The baby inherits the home only beside the parent who holds it: put them down once the parent stands at her
                // own bed (the census then finds them together), or the wait has run out. A child that already has the home
                // is released on its own in childReason.
                var stand = carrier.life().homeStand();
                if (stand == null || carrier.distanceToSqr(Vec3.atBottomCenterOf(stand)) <= 9) return "arrived_home";
                if (now - arrivedAt > ARRIVE_GRACE_TICKS) return "arrival_timeout";
            }
            if (!landed && !carrier.settlement().familyMarching()) return "migration_ended";
        }
        return null;
    }

    /** Reasons that end the ride of one child only. */
    private String childReason(YukkuriEntity child, long now) {
        if (!child.isAlive()) return "child_died";
        var problem = holdProblem(carrier, child, reach(carrier, child), true);
        if (problem != null) return problem;
        if (child.isInWaterOrBubble()) return "water";
        if (child.operations().active()) return "operation";
        if (now % 5 == 0 && seat(carrier, child) == null) return "seat_blocked";
        if (child.life().nutrition() < 30 && carrier.family().feeding().hasCarriedOpportunity(child)) return "feeding";
        if (purpose == Purpose.MIGRATE && carrier.settlement().migrationLanded() && child.settlement().migrationLanded()) return "arrived_destination";
        return null;
    }

    /** Walk home with the child (retrieval) on the shared Walk; false once the walk is over. */
    boolean tickHome(int taskTicks) {
        var child = carried();
        if (child == null) return false;
        if (purpose == Purpose.NEST_RIM) return tickRim(child, taskTicks);
        var home = child.family().carryHome();
        if (home == null) { putDown("no_home", HOLD_TICKS); return false; }
        var centre = Vec3.atBottomCenterOf(home);
        if (carrier.distanceToSqr(centre) <= 2.5 * 2.5) { putDown("arrived_home", 0); return false; }
        var floor = SurfaceApproach.restingFloor(carrier, centre.add(0, .1, 0));
        return walk(floor == null ? centre : floor, taskTicks, "home_unreachable");
    }

    /** Carry a baby that lay on the nest rim onto the cushion and set it down at a free spot by the nest's own placement (never moved by teleport). */
    private boolean tickRim(YukkuriEntity child, int taskTicks) {
        var nest = rimNest != null && carrier.level().getBlockEntity(rimNest) instanceof CareBlockEntity c ? c : null;
        if (nest == null) { putDown("no_nest", HOLD_TICKS); return false; }
        if (rimSpot == null || taskTicks % 20 == 1) {
            var spot = nest.freeSpot(child);
            if (spot == null) {
                if (++homeFailures >= 3) {
                    GameplayLog.event(carrier, "family", "carry nest_rim refused child={} reason=no_free_cushion_spot nest={}", child.getUUID(), rimNest.toShortString());
                    putDown("nest_full", HOLD_TICKS);
                    return false;
                }
            } else {
                if (rimSpot == null) GameplayLog.event(carrier, "family", "carry nest_rim target child={} nest={} spot={}", child.getUUID(), rimNest.toShortString(), spot);
                rimSpot = spot;
                homeFailures = 0;
            }
        }
        if (taskTicks > 600) { putDown("nest_unreachable", HOLD_TICKS); return false; }
        if (rimSpot == null) return true;
        var floor = SurfaceApproach.restingFloor(carrier, rimSpot.add(0, .1, 0));
        var at = floor == null ? rimSpot : floor;
        double dx = at.x - carrier.getX(), dz = at.z - carrier.getZ(), near = dx * dx + dz * dz;
        if (near <= .45 * .45 || near <= .9 * .9 && FamilyNest.cushions(carrier)) {
            putDown("nest_rim_to_cushion", HOLD_TICKS);
            return false;
        }
        return walk(at, taskTicks, "nest_unreachable");
    }

    /**
     * The carry's Walk to {@code at} (spec 9.4 Carry: the shared Walk, slowed by the load). A walk that ended short of the
     * same spot, or would not start, is tried again every ten ticks; the third such try ends the carry where the child is.
     */
    private boolean walk(Vec3 at, int taskTicks, String failure) {
        var motor = carrier.motor();
        boolean same = walkTo != null && at.distanceToSqr(walkTo) < .0625;
        if (same && motor.current() instanceof Walk && motor.owner() == owner) return true;
        if (same && taskTicks % 10 != 1) return true;
        if (same && ++walkFailures >= 3) { putDown(failure, HOLD_TICKS); return false; }
        walkTo = at;
        motor.run(owner, new Walk(at));
        return true;
    }

    /** Set every rider down where it is (see {@link #putDown(YukkuriEntity, String, int)}). Returns false when nothing was carried. */
    public boolean putDown(String reason, int holdTicks) {
        var all = riders();
        for (var child : all) putDown(child, reason, holdTicks);
        return !all.isEmpty();
    }

    /**
     * Set one child down where it actually hangs; the others keep riding. Inside a decision the put-down is the
     * {@link Release} action's own check, so no room means the carrier keeps carrying and turns or steps first; on a passive
     * tick, an interrupt or a death the body is set down at once, because there is no decision left to turn or step in.
     */
    public boolean putDown(YukkuriEntity child, String reason, int holdTicks) {
        if (!carrier.hasPassenger(child) || !(carrier.level() instanceof ServerLevel)) return false;
        var reach = reach(carrier, child);
        Vec3 before = child.position();
        boolean deciding = BodyDriver.deciding();
        var room = Release.now(carrier, child, !deciding, deciding ? "release" : "carry_" + reason);
        if (!room.ok()) {
            GameplayLog.limited(carrier, "family", "carry.put_down.refused", 100,
                    "carry put down refused reason={} why={} reach={} child={} at={}", reason, room.reason(), name(reach), child.getUUID(), GameplayLog.at(before));
            return false;
        }
        if (child.getUUID().equals(lifting)) lifting = null;
        child.fallDistance = 0;
        long now = carrier.level().getGameTime();
        if (holdTicks > 0) holds.put(child.getUUID(), new Hold(now + holdTicks, reason));
        GameplayLog.event(carrier, "family", "carry put down reason={} reach={} purpose={} method={} child={} before={} after={} carriedTicks={} nutrition={} hold={} remaining={}",
                reason, name(reach), purpose, deciding ? "release" : "in_place", child.getUUID(), before, child.position(), now - since,
                child.life().nutrition(), holdTicks, carrier.getPassengers().size());
        if (!carrying()) {
            purpose = null;
            if (carrier.motor().current() instanceof Walk) carrier.motor().cancel(owner, "carry_ended");
            carrier.family().endCarry();
        }
        return true;
    }

    /** Carrier interruptions and the carried child's own (hurt, burning, command, death) end the ride at the real position. */
    void interrupted(String why, boolean childSide) {
        if (!childSide) { if (carrying()) putDown("carrier_" + why, HOLD_TICKS); return; }
        if (carrier.getVehicle() instanceof YukkuriEntity parent && riding(parent, carrier)) {
            parent.family().carry().putDown(carrier, "child_" + why, HOLD_TICKS);
        }
    }
    public void dropAll(String reason) { if (carrying()) putDown(reason, 0); }

    String describe() {
        var all = riders();
        if (!all.isEmpty()) {
            var ids = new StringBuilder();
            for (var child : all) ids.append(ids.length() == 0 ? "" : ",").append(child.getUUID()).append('/').append(name(reach(carrier, child)));
            return "carrying count=" + all.size() + " children=" + ids + " purpose=" + purpose + " ticks="
                    + (carrier.level().getGameTime() - since) + " landed=" + carrier.settlement().migrationLanded();
        }
        if (carrier.getVehicle() instanceof YukkuriEntity parent && riding(parent, carrier))
            return "carried_by=" + parent.getUUID() + " reach=" + name(reach(parent, carrier));
        return "none holds=" + holds.size();
    }
}
