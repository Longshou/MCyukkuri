package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriLocomotion;
import io.github.longshou.mcyukkuri.subspecies.boat.VoyagePhase;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A parent carries a dependent baby over land with a real passenger (the same custody as the hat brim): brim for hat
 * Marisa (as many as physically fit the brim, then one in the mouth), one per tail for Chen (two), mouth for everyone else (one;
 * Reimu holds several, within a volume budget). Pick-up needs reach, capacity and a free carrier;
 * the child rides until it is put down at its own position (never copied, never teleported to a goal) for a real reason.
 * Client code reads only {@link #mode} / {@link #rawSeat} / {@link #mouthLane}; the drawn pose follows the live carrier
 * model in client ParentCarryPresentation.
 */
public final class ParentCarry {
    public enum Mode { HAT, MOUTH, TAIL }
    public enum Purpose { MIGRATE, RETRIEVE, NEST_RIM, MANUAL, BOAT_BOARD }
    public enum Step { WORKING, DONE, FAILED }
    /** Ticks the parent stands over the child before it is lifted. */
    static final int LIFT_TICKS = 14;
    /** Pause before the same child is picked up again after an interruption; feeding gets longer. */
    static final int HOLD_TICKS = 100, FEED_HOLD_TICKS = 200;
    /** After the carrier has settled at the destination the child is kept at most this long while it inherits the home. */
    static final int ARRIVE_GRACE_TICKS = 600;
    private static final int MAX_CARRY_TICKS = 12000;
    /** Reimu holds several babies in her mouth (project design): their summed volume stays within this fraction of her own, scaled by strength. */
    static final double MOUTH_LOAD_FRACTION = .45;
    static final int MAX_MOUTH_BABIES = 4;
    /** Chen's tails: one baby per tail, each up to this fraction of her volume times strength (project design). */
    static final int TAIL_COUNT = 2;
    static final double TAIL_BABY_FRACTION = .26;
    /** Tail seat: the shelf of the curled tail behind and beside the body (model units, idle pose, full curl). */
    private record Tail(double x, double y, double z) {}
    /** One seat per tail (left, right), model units; matches the curl drawn by ParentCarryPresentation (the client follows the live tail). */
    private static final Map<String, Tail[]> TAILS = Map.of(
            "chen", new Tail[]{new Tail(-3.09, 6.46, 8.6), new Tail(3.09, 6.46, 8.6)},
            "chen_longtail", new Tail[]{new Tail(-2.93, 6.82, 9.05), new Tail(2.93, 6.82, 9.05)},
            "chen_roundtail", new Tail[]{new Tail(-2.95, 4.99, 8.78), new Tail(2.95, 4.99, 8.78)});
    /**
     * Mouth lanes (project design): children hang side by side across the lips in seat order, each gripped at the top-rear of
     * its head; the closed lips stretch a little for each extra child, and a child whose lane lies past them is held by its
     * inner side lock instead (lower on the head, so it rides a little higher and fans outward). Carrier model units.
     */
    static final double LANE_GAP = .12, MOUTH_HALF = 1.6, LIP_STRETCH = .25, GRIP_SIDE = 5.2, GRIP_DROP = 3.2, LANE_FAN = 12;
    /** The grip on a child's head (child model units): top-rear of the head, the scruff. */
    public static final double GRIP_Y = 8.2, GRIP_Z = 4.0;
    /** The bite sits this far in front of the lip plane (carrier model units), so the hanging back clears the face; lanes
     *  out at the cheeks come forward a little more (per squared lane offset) to clear the side locks. */
    public static final double LIP_GAP = .8, LANE_FORWARD = .035;
    /** Forward offset of a lane's bite from the lip plane (carrier model units). */
    public static double biteForward(MouthLane lane) { return LIP_GAP + LANE_FORWARD * lane.side() * lane.side(); }
    /** The lowest a mouth-held child's feet hang above the carrier's feet (carrier model units). */
    public static final double MOUTH_FLOOR = .2;
    private record Hold(long until, String reason) {}

    private final YukkuriEntity carrier;
    private final Map<UUID, Hold> holds = new HashMap<>();
    private Purpose purpose;
    private long since, arrivedAt;
    private int lift, homeFailures;
    private UUID lifting;
    /** NEST_RIM: the nest whose rim the child lay on, and the free cushion spot being walked to. */
    private BlockPos rimNest;
    private Vec3 rimSpot;

    ParentCarry(YukkuriEntity carrier) { this.carrier = carrier; }

    // ------------------------------------------------------------------------------------------ shared geometry

    /** Where one carried child sits: the hold, its place within that hold, how many share it, and the brim offset. */
    public record Seat(Mode mode, int lane, int lanes, double dz) {}

    /**
     * Seat of this child, from synchronized size and form only, so both sides agree. Riders are placed in boarding order
     * (a child not yet aboard comes last): on a hatted Marisa's brim while real brim room is left, otherwise on Chen's
     * tails or in the mouth.
     */
    public static Seat resolve(YukkuriView carrier, YukkuriView child) {
        var all = new ArrayList<>(carrier.passengerViews());
        int me = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).getUUID().equals(child.getUUID())) me = i;
        if (me < 0) { all.add(child); me = all.size() - 1; }
        int n = all.size();
        boolean brim = HatCarry.hasHat(carrier) && carrier.hasVisibleAccessory();
        double[] dz = null;
        if (brim) {
            var ids = new ArrayList<String>();
            var ratios = new ArrayList<Double>();
            for (var rider : all) { ids.add(rider.assetId()); ratios.add(size(rider) / size(carrier)); }
            dz = HatCarry.layout(carrier.assetId(), ids, ratios);
        }
        boolean chen = carrier.getVariant() == YukkuriVariant.CHEN;
        Mode[] modes = new Mode[n];
        int[] lane = new int[n], used = new int[3];
        for (int i = 0; i < n; i++) {
            modes[i] = brim && !Double.isNaN(dz[i]) ? Mode.HAT : chen ? Mode.TAIL : Mode.MOUTH;
            lane[i] = used[modes[i].ordinal()]++;
        }
        return new Seat(modes[me], lane[me], used[modes[me].ordinal()], modes[me] == Mode.HAT ? dz[me] : 0);
    }
    /** How this carrier holds this child (see {@link #resolve}). */
    public static Mode mode(YukkuriView carrier, YukkuriView child) {
        return carrier == null || child == null ? null : resolve(carrier, child).mode();
    }
    /** What the carrier is doing with its riders: the brim if any rider is on it, else its first rider's hold; null when empty. */
    public static Mode carryMode(YukkuriView carrier) {
        if (!carrier.isVehicle()) return null;
        Mode first = null;
        for (var rider : carrier.passengerViews()) {
            var mode = mode(carrier, rider);
            if (mode == Mode.HAT) return mode;
            if (first == null) first = mode;
        }
        return first;
    }
    /** True while the child is a passenger of this carrier. */
    public static boolean visibleCarried(YukkuriView carrier, YukkuriView child) { return carrier.hasPassengerId(child.getUUID()); }
    public static boolean riding(YukkuriEntity carrier, Entity child) { return child instanceof YukkuriEntity && carrier.hasPassenger(child); }
    private static double size(YukkuriView v) { return v.growthScale() * v.getScale(); }
    /** Reimu carries babies in her mouth, several at once; every other mouth carrier holds one. */
    public static boolean multi(YukkuriView carrier) { return carrier.getVariant() == YukkuriVariant.REIMU; }

    /** Feet of the carried child before any collision adjustment (also what the client draws for the brim). */
    public static Vec3 rawSeat(YukkuriView carrier, YukkuriView child, float partial) {
        var seat = resolve(carrier, child);
        if (seat.mode() == Mode.HAT) return HatCarry.seatWorld(carrier, partial, seat.dz());
        float scale = (float) size(carrier);
        float yaw = carrier.level().isClientSide ? Mth.rotLerp(partial, carrier.previousBodyYaw(), carrier.bodyYaw()) : carrier.bodyYaw();
        float turn = (180 - yaw) * Mth.DEG_TO_RAD;
        var base = carrier.level().isClientSide ? carrier.getPosition(partial) : carrier.position();
        if (seat.mode() == Mode.TAIL) {
            var tails = TAILS.getOrDefault(carrier.assetId(), TAILS.get("chen"));
            var tail = tails[Math.min(seat.lane(), tails.length - 1)];
            var point = new Vector3f((float) tail.x(), (float) tail.y(), (float) tail.z()).mul(scale / 16).rotateY(turn);
            return base.add(point.x, point.y, point.z);
        }
        // Feet under the lane's grip at rest: the client hangs the child from the live lip (ParentCarryPresentation).
        var lane = mouthLane(carrier, child);
        var mouth = MouthPlacement.anchor(carrier);
        double rho = size(child) / Math.max(1e-6, size(carrier));
        // The anchor lies about .35 in front of the lip plane the client grips at.
        var feet = new Vector3f((float) lane.side(), (float) Math.max(MOUTH_FLOOR, mouth.y - lane.gripY() * rho),
                (float) (mouth.z + .35 - biteForward(lane) - lane.gripZ() * rho)).mul(scale / 16).rotateY(turn);
        return base.add(feet.x, feet.y, feet.z);
    }
    /** Where one mouth-held child hangs: lane centre across the lips, grip on its head, outward fan and side tilt (degrees). */
    public record MouthLane(double side, double gripX, double gripY, double gripZ, float fan, float roll) {}
    /** The lane of this child among the carrier's mouth-held riders (seat order; a child not yet aboard comes last). */
    public static MouthLane mouthLane(YukkuriView carrier, YukkuriView child) {
        var riders = new ArrayList<YukkuriView>();
        boolean found = false;
        for (var rider : carrier.passengerViews()) {
            if (rider.getUUID().equals(child.getUUID())) { riders.add(rider); found = true; }
            else if (mode(carrier, rider) == Mode.MOUTH) riders.add(rider);
        }
        if (!found) riders.add(child);
        int n = riders.size(), me = 0;
        double carrierSize = Math.max(1e-6, size(carrier)), width = (n - 1) * LANE_GAP;
        double[] half = new double[n];
        for (int i = 0; i < n; i++) {
            half[i] = HatCarry.childHalfWidth(riders.get(i).assetId()) * size(riders.get(i)) / carrierSize;
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
    public static boolean mouthCarrying(YukkuriView carrier) {
        if (!carrier.isVehicle()) return false;
        for (var rider : carrier.passengerViews()) if (mode(carrier, rider) == Mode.MOUTH) return true;
        return false;
    }

    /** Seat used by the passenger position update: the raw seat pulled toward the carrier's own cell until the child fits. */
    public static Vec3 seat(YukkuriEntity carrier, YukkuriEntity child) {
        var mode = mode(carrier, child);
        // The client has already moved the carrier this tick: use its current pose (partial 1) so the child does not trail it.
        var raw = rawSeat(carrier, child, mode == Mode.HAT ? 0 : 1);
        if (mode == Mode.HAT) return raw;
        for (double t : new double[]{0, .35, .7, 1}) {
            var at = new Vec3(Mth.lerp(t, raw.x, carrier.getX()), raw.y, Mth.lerp(t, raw.z, carrier.getZ()));
            if (clear(carrier, child, at)) return at;
        }
        return null;
    }
    private static boolean clear(YukkuriEntity carrier, YukkuriEntity child, Vec3 at) {
        var box = child.getDimensions(child.getPose()).makeBoundingBox(at).deflate(.02);
        return !carrier.level().getBlockCollisions(child, box).iterator().hasNext() && !carrier.level().containsAnyLiquid(box);
    }

    // ------------------------------------------------------------------------------------------ capacity and rules

    /** Dependents that cannot keep up on their own: babies, and young crawlers (hurt foot, not yet able to jump). */
    public static boolean needsCarry(YukkuriEntity child) {
        return child.isAlive() && !child.family().adult() && !child.family().awaitingLanding() && child.childhood().shouldStayWithParents()
                && (child.family().stage() == FamilyLife.Stage.BABY || child.canCrawlNow());
    }
    private static double volume(YukkuriEntity e) { return e.getBbWidth() * e.getBbWidth() * e.getBbHeight(); }
    private static double strength(YukkuriEntity carrier) {
        return Math.clamp((carrier.life().energy() - 8) / 60.0, .2, 1) * Math.clamp(carrier.species().traits().might(), .8, 1.5);
    }
    private static boolean mouthFree(YukkuriEntity carrier) {
        return carrier.getMouthItem().isEmpty() && carrier.getMouthBodyFood().isEmpty() && carrier.habitat().carrying().isEmpty();
    }
    /** Why this carrier could not hold this child at all (size, form, an occupied seat, wrong stage); null when it can. */
    static String capacityProblem(YukkuriEntity carrier, YukkuriEntity child, boolean ongoing, boolean full) {
        if (carrier == child) return "self";
        if (!carrier.isAlive() || !child.isAlive()) return "dead";
        if (carrier.level() != child.level()) return "level";
        if (!carrier.family().adult() || child.family().adult()) return "stage";
        if (carrier.profile().aquatic() && !boatBoarding(carrier, child) || carrier.profile().houseBody() || carrier.profile().dangerToYukkuri()) return "carrier_kind";
        if (carrier.isPassenger()) return "carrier_boarded";
        if (child.isVehicle()) return "child_carrying";
        if (child.isPassenger() && child.getVehicle() != carrier) return "child_boarded";
        if (carrier.livePregnancyProgress() > 0) return "pregnant";
        var seat = resolve(carrier, child);
        double ratio = volume(child) / Math.max(1e-6, volume(carrier));
        switch (seat.mode()) {
            case HAT -> { if (!ongoing && full && !HatCarry.canSeat(carrier, child)) return "hat_seat"; }
            case TAIL -> {
                // Two tails, one baby each; each tail takes a baby up to this share of the carrier's volume (project design).
                if (seat.lane() >= TAIL_COUNT) return "occupied";
                if (ratio > TAIL_BABY_FRACTION * strength(carrier)) return "too_big";
            }
            case MOUTH -> {
                if (ratio > .20 * strength(carrier)) return "too_big";
                if (!mouthFree(carrier)) return "mouth_full";
                if (!multi(carrier)) { if (seat.lane() >= 1) return "occupied"; }
                else {
                    // Babies already aboard (for a rider: those ahead of it in seat order) share the budget.
                    double load = volume(child);
                    int count = 1;
                    for (var rider : carrier.getPassengers()) {
                        if (rider == child) { if (ongoing) break; continue; }
                        if (rider instanceof YukkuriEntity other) { load += volume(other); count++; }
                    }
                    if (count > MAX_MOUTH_BABIES) return "occupied";
                    if (load > MOUTH_LOAD_FRACTION * strength(carrier) * volume(carrier)) return "mouth_load";
                }
            }
        }
        return null;
    }
    /** Vanilla seat acceptance for forced and unforced boarding (YukkuriEntity.canAddPassenger). */
    public static boolean canSeat(YukkuriEntity carrier, Entity passenger) {
        if (!(passenger instanceof YukkuriEntity child)) return false;
        return capacityProblem(carrier, child, false, true) == null && !carrier.isInWaterOrBubble() && !child.isInWaterOrBubble()
                && !carrier.operations().active() && !child.operations().active();
    }
    /** A shore transport belongs to this live loading manifest; aquatic life still owns its destination and timeout. */
    private static boolean boatBoarding(YukkuriEntity parent, YukkuriEntity child) {
        return parent.aquaticLife().voyage().phase() == VoyagePhase.LOAD
                && parent.aquaticLife().voyage().awaiting(child.getUUID()) && Bonds.parentOf(parent, child);
    }
    private boolean boardingReady(YukkuriEntity child) {
        return boatBoarding(carrier, child) && carrier.isAlive() && !carrier.isNoAi() && !carrier.isPassenger()
                && !carrier.isInWaterOrBubble() && !carrier.isCommandPlaying() && !carrier.operations().active()
                && !carrier.family().laboring() && !carrier.family().birthDuty() && !carrier.family().crew().busy()
                && !carrier.isPanicking() && !carrier.isOnFire() && !carrier.life().isSleeping()
                && carrier.life().mealTicks() == 0 && !carrier.life().hasUrgentNeed();
    }
    /** Reuse the real land pickup and passenger presentation before the final stationary braid lift into a hat. */
    public Step tickBoardingPickup(YukkuriEntity child) { return tickPickup(child, Purpose.BOAT_BOARD); }
    public boolean boardingPickupActive() { return lifting != null && purpose == Purpose.BOAT_BOARD; }

    /** The carrier is not busy with something that needs its body, mouth or attention right now. */
    private boolean ready() {
        var life = carrier.life();
        return FamilyLife.ordinaryLandFree(carrier) && !carrier.isPanicking() && !carrier.isOnFire() && !life.isSleeping()
                && life.mealTicks() == 0 && (!life.hasUrgentNeed() || carrier.settlement().marchTiredOnly());
    }
    /** Structural ability only (size, form, free seat); the child waits for such a carrier instead of walking off. */
    public boolean capable(YukkuriEntity child) { return capacityProblem(carrier, child, false, false) == null; }

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
     * A mouth-carrier with babies to carry keeps the mouth for them and packs no food: the mother always does; the father
     * only when the mother cannot take them all (project design, so one parent still brings the rations).
     */
    public static boolean reservesMouth(YukkuriEntity parent) {
        if (!parent.family().adult() || !(parent.level() instanceof ServerLevel level)) return false;
        var kids = level.getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(24),
                c -> c != parent && needsCarry(c) && Bonds.parentOf(parent, c));
        if (kids.stream().noneMatch(c -> mode(parent, c) == Mode.MOUTH)) return false;
        if (kids.stream().anyMatch(c -> parent.getUUID().equals(c.family().mother()))) return true;
        var first = kids.get(0);
        var mother = first.family().mother() != null && level.getEntity(first.family().mother()) instanceof YukkuriEntity m && m.isAlive()
                && m.family().adult() ? m : null;
        return kids.size() > (mother == null ? 0 : mother.family().carry().seatsFor(kids));
    }

    /** Babies like {@code child} this carrier could take from now on, this one included: 0 if it cannot take it. */
    int room(YukkuriEntity child) {
        if (!capable(child)) return 0;
        var seat = resolve(carrier, child);
        switch (seat.mode()) {
            case TAIL: return Math.max(0, TAIL_COUNT - seat.lane());
            case HAT: {
                // Add copies of this baby behind the current riders until the brim has no more room.
                var ids = new ArrayList<String>();
                var ratios = new ArrayList<Double>();
                for (var rider : carrier.passengerViews()) { ids.add(rider.assetId()); ratios.add(size(rider) / size(carrier)); }
                int base = ids.size();
                for (int k = 0; k < 6; k++) { ids.add(child.assetId()); ratios.add(size(child) / size(carrier)); }
                var dz = HatCarry.layout(carrier.assetId(), ids, ratios);
                int fit = 0;
                for (int i = base; i < dz.length; i++) if (!Double.isNaN(dz[i])) fit++;
                // A baby that does not fit the brim can still take the free mouth.
                return Math.max(1, fit);
            }
            default: {
                if (!multi(carrier)) return 1;
                double load = 0;
                for (var rider : carrier.getPassengers()) if (rider instanceof YukkuriEntity other) load += volume(other);
                double budget = MOUTH_LOAD_FRACTION * strength(carrier) * volume(carrier);
                int byVolume = (int) Math.floor((budget - load) / Math.max(1e-6, volume(child)));
                return Math.max(1, Math.min(byVolume, MAX_MOUTH_BABIES - carrier.getPassengers().size()));
            }
        }
    }

    /** How many of these babies this carrier could hold at once from empty (father's share of carrying is what is left). */
    int seatsFor(List<YukkuriEntity> kids) {
        if (multi(carrier)) {
            double budget = MOUTH_LOAD_FRACTION * strength(carrier) * volume(carrier), load = 0;
            int count = 0;
            for (var kid : kids) {
                if (count >= MAX_MOUTH_BABIES || !capable(kid) && !carrier.hasPassenger(kid)) continue;
                if (load + volume(kid) > budget) continue;
                load += volume(kid);
                count++;
            }
            return count;
        }
        if (carrier.getVariant() == YukkuriVariant.CHEN) return Math.min(TAIL_COUNT, (int) kids.stream().filter(this::capable).count());
        if (HatCarry.hasHat(carrier) && carrier.hasVisibleAccessory()) {
            var ids = new ArrayList<String>();
            var ratios = new ArrayList<Double>();
            for (var kid : kids) { ids.add(kid.assetId()); ratios.add(size(kid) / size(carrier)); }
            int fit = 0;
            for (double z : HatCarry.layout(carrier.assetId(), ids, ratios)) if (!Double.isNaN(z)) fit++;
            return fit + (fit < kids.size() && mouthFree(carrier) ? 1 : 0);
        }
        return kids.stream().anyMatch(this::capable) ? 1 : 0;
    }

    // ------------------------------------------------------------------------------------------ pick-up (server)

    /** The first rider (the one a retrieval walk and the readout follow). */
    public YukkuriEntity carried() { return carrier.getFirstPassenger() instanceof YukkuriEntity y ? y : null; }
    public boolean carrying() { return carried() != null; }
    public Purpose purpose() { return purpose; }
    /** Every yukkuri riding this carrier (more than one only for Reimu). */
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
    boolean pickupWanted(YukkuriEntity child) { return purposeFor(child) != null && capable(child); }
    /** During a march a baby near the parent still has to be lifted before the parent walks on. */
    boolean pickupDue(YukkuriEntity child) {
        var why = purposeFor(child);
        return (why == Purpose.MIGRATE || why == Purpose.NEST_RIM) && capable(child) && ready();
    }

    private boolean inReach(YukkuriEntity child) {
        double dx = child.getX() - carrier.getX(), dz = child.getZ() - carrier.getZ();
        double reach = (carrier.getBbWidth() + child.getBbWidth()) / 2 + .35;
        return dx * dx + dz * dz <= reach * reach && Math.abs(child.getY() - carrier.getY()) < 1.2 && carrier.hasLineOfSight(child);
    }

    /** Standing over a child and counting down its lift (shown as the lean-down of carry_lift). */
    boolean lifting() { return lifting != null; }
    /** One tick of walking to the child, standing over it and lifting it. */
    Step tickPickup(YukkuriEntity child, int taskTicks) { return tickPickup(child, purposeFor(child)); }
    private Step tickPickup(YukkuriEntity child, Purpose intent) {
        var why = capacityProblem(carrier, child, false, true);
        boolean ready = intent == Purpose.BOAT_BOARD ? boardingReady(child) : ready();
        if (why != null || !ready) {
            GameplayLog.limited(carrier, "family", "carry.refused", 400, "carry pickup refused child={} reason={} ready={}", child.getUUID(),
                    why == null ? "carrier_busy" : why, ready);
            return Step.FAILED;
        }
        if (!inReach(child)) {
            lift = 0;
            lifting = null;
            var stand=SurfaceApproach.beside(carrier,child,(carrier.getBbWidth()+child.getBbWidth())*.5+.15,1.2);
            if(stand==null){
                GameplayLog.limited(carrier,"family","carry.approach",200,"carry pickup refused child={} reason=no_supported_contact_stand",child.getUUID());
                return Step.FAILED;
            }
            SurfaceApproach.walkAround(carrier,stand,child,1.1);
            return Step.WORKING;
        }
        carrier.getNavigation().stop();
        carrier.getLookControl().setLookAt(child, 30, 30);
        if (intent == Purpose.BOAT_BOARD) {
            float yaw = SurfaceApproach.yaw(carrier.position(), child.position());
            SurfaceApproach.face(carrier, yaw);
            if (Math.abs(Mth.wrapDegrees(yaw - carrier.yBodyRot)) > 3) return Step.WORKING;
        }
        if (child.life().mealTicks() > 0 || child.isPanicking() || child.isInWaterOrBubble() || child.operations().active()) return Step.WORKING;
        if (!child.getUUID().equals(lifting)) {
            lifting = child.getUUID();
            lift = 0;
            if (intent == Purpose.BOAT_BOARD) purpose = intent;
            GameplayLog.event(carrier, "family", "carry lift started mode={} purpose={} child={} childPos={} carrierPos={} nutrition={}",
                    mode(carrier, child), intent, child.getUUID(), child.blockPosition().toShortString(),
                    carrier.blockPosition().toShortString(), child.life().nutrition());
        }
        if (++lift < LIFT_TICKS) return Step.WORKING;
        return board(child, intent) ? Step.DONE : Step.FAILED;
    }

    private boolean board(YukkuriEntity child, Purpose intent) {
        var rim = intent == Purpose.NEST_RIM ? FamilyNest.rimNest(child) : null;
        var mode = mode(carrier, child);
        Vec3 before = child.position();
        child.life().interrupt();
        child.family().stop();
        child.social().stop();
        child.dailyLife().stop();
        child.getNavigation().stop();
        if (!child.startRiding(carrier, true)) {
            GameplayLog.event(carrier, "family", "carry pickup failed child={} reason=start_riding_rejected", child.getUUID());
            return false;
        }
        carrier.setPersistenceRequired();
        child.setPersistenceRequired();
        child.fallDistance = 0;
        lifting = null;
        lift = 0;
        // A further baby joins the carry already under way; the first one set the purpose and the clock.
        if (purpose == null || intent == Purpose.BOAT_BOARD) {
            purpose = intent == null ? Purpose.RETRIEVE : intent;
            since = carrier.level().getGameTime();
            arrivedAt = 0;
            homeFailures = 0;
            rimSpot = null;
            rimNest = purpose == Purpose.NEST_RIM && rim != null ? rim.getBlockPos() : null;
        }
        holds.remove(child.getUUID());
        GameplayLog.event(carrier, "family", "carry pickup mode={} purpose={} child={} childStage={} before={} seat={} nutrition={} volume={}/{} aboard={}",
                mode, purpose, child.getUUID(), child.family().stage(), before, child.position(), child.life().nutrition(),
                String.format("%.3f", volume(child)), String.format("%.3f", volume(carrier)), carrier.getPassengers().size());
        carrier.showReaction("happy", "gentle", 40);
        return true;
    }

    // ------------------------------------------------------------------------------------------ carrying (server)

    /** Per tick on the carrier: the one place that decides whether each child still rides. */
    void tick() {
        var all = riders();
        if (all.isEmpty()) {
            if (purpose == Purpose.BOAT_BOARD && carrier.aquaticLife().voyage().phase() != VoyagePhase.LOAD) {
                lifting = null; lift = 0;
            }
            if (purpose != null && lifting == null) purpose = null;
            return;
        }
        long now = carrier.level().getGameTime();
        if (purpose == null) adopt(all.get(0), now);
        String why = carrierReason(all.get(0), now);
        if (why != null) { putDown(why, HOLD_TICKS); return; }
        for (var child : all) {
            String childWhy = childReason(child, now);
            if (childWhy != null) putDown(child, childWhy, childWhy.equals("feeding") ? FEED_HOLD_TICKS : HOLD_TICKS);
        }
        if (carrying() && (purpose == Purpose.RETRIEVE || purpose == Purpose.NEST_RIM)) carrier.family().beginCarry();
    }

    /** A player-ordered hat carry: it stays until a safety reason or the player puts the child down. */
    void manual(YukkuriEntity child) {
        purpose = Purpose.MANUAL;
        since = carrier.level().getGameTime();
        holds.remove(child.getUUID());
        GameplayLog.event(carrier, "family", "carry pickup mode={} purpose=MANUAL child={} seat={}", mode(carrier, child), child.getUUID(), child.position());
    }

    /** A passenger found without live state (reloaded save, other pick-up path): carry on with the rule that applies now. */
    private void adopt(YukkuriEntity child, long now) {
        purpose = carrier.settlement().familyMarching() ? Purpose.MIGRATE
                : needsCarry(child) && child.family().carryHome() != null ? Purpose.RETRIEVE : Purpose.MANUAL;
        since = now;
        arrivedAt = 0;
        homeFailures = 0;
        GameplayLog.event(carrier, "family", "carry resumed mode={} purpose={} child={}", mode(carrier, child), purpose, child.getUUID());
        if (purpose == Purpose.RETRIEVE) carrier.family().beginCarry();
    }

    /** Reasons that end the whole carry (the carrier's own state, or the shared destination rule). */
    private String carrierReason(YukkuriEntity first, long now) {
        if (!carrier.isAlive()) return "carrier_died";
        if (carrier.isInWaterOrBubble()) return "water";
        var life = carrier.life();
        if (carrier.isOnFire()) return "carrier_burning";
        if (carrier.isPanicking()) return "carrier_panic";
        if (purpose != Purpose.MANUAL) {
            if (life.isSleeping()) return "carrier_sleeping";
            if (life.mealTicks() > 0 && mouthBusy()) return "carrier_eating";
            if (life.hasUrgentNeed() && !carrier.settlement().marchTiredOnly()) return "carrier_need";
        }
        if (carrier.operations().active()) return "operation";
        if (now - since > MAX_CARRY_TICKS) return "timeout";
        if (purpose == Purpose.BOAT_BOARD && !boatBoarding(carrier, first)) return "boarding_ended";
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
        var problem = capacityProblem(carrier, child, true, false);
        if (problem != null) return problem;
        if (child.isInWaterOrBubble()) return "water";
        if (child.operations().active()) return "operation";
        if (now % 5 == 0 && mode(carrier, child) != Mode.HAT && seat(carrier, child) == null) return "seat_blocked";
        if (purpose != Purpose.MANUAL && child.life().nutrition() < 30 && carrier.family().feeding().hasCarriedOpportunity(child)) return "feeding";
        if (purpose == Purpose.MIGRATE && carrier.settlement().migrationLanded() && child.settlement().migrationLanded()) return "arrived_destination";
        return null;
    }

    /** Walk home with the child (retrieval); false once the walk is over. */
    boolean tickHome(int taskTicks) {
        var child = carried();
        if (child == null) return false;
        if (purpose == Purpose.NEST_RIM) return tickRim(child, taskTicks);
        var home = child.family().carryHome();
        if (home == null) { putDown("no_home", HOLD_TICKS); return false; }
        if (carrier.distanceToSqr(Vec3.atBottomCenterOf(home)) <= 2.5 * 2.5) { putDown("arrived_home", 0); return false; }
        if (taskTicks % 10 == 1 && !carrier.life().navigate(home, 1.0, false)) {
            if (++homeFailures >= 3) { putDown("home_unreachable", HOLD_TICKS); return false; }
        }
        return true;
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
            SurfaceApproach.halt(carrier);
            putDown("nest_rim_to_cushion", HOLD_TICKS);
            return false;
        }
        SurfaceApproach.walk(carrier, at, 1.0);
        return true;
    }

    public boolean putDownForBoarding(YukkuriEntity child) {
        if (purpose != Purpose.BOAT_BOARD || !carrier.hasPassenger(child)) return false;
        var feet = child.position(); var ground = SurfaceApproach.floor(child, feet);
        if (ground == null || feet.y < ground.y - .002 || feet.y - ground.y > .25) return false;
        // Ordinary stopRiding chooses a new side cell. Keep these measured hanging feet and let the short drop settle naturally.
        YukkuriLocomotion.detach(child);
        child.setDeltaMovement(Vec3.ZERO); child.fallDistance = 0;
        GameplayLog.event(carrier, "family", "carry put down reason=boarding_near_bank mode={} purpose=BOAT_BOARD child={} before={} after={} floor={} carriedTicks={}",
                mode(carrier, child), child.getUUID(), feet, child.position(), ground, carrier.level().getGameTime() - since);
        purpose = null; lifting = null; lift = 0;
        return true;
    }

    /** Set every rider down where it is (see {@link #putDown(YukkuriEntity, String, int)}). Returns false when nothing was carried. */
    public boolean putDown(String reason, int holdTicks) {
        var all = riders();
        for (var child : all) putDown(child, reason, holdTicks);
        return !all.isEmpty();
    }

    /** Set one child down where it is (nudged clear of blocks only if needed); the others keep riding. */
    public boolean putDown(YukkuriEntity child, String reason, int holdTicks) {
        if (!carrier.hasPassenger(child) || !(carrier.level() instanceof ServerLevel)) return false;
        var mode = mode(carrier, child);
        Vec3 before = child.position();
        if (purpose == Purpose.BOAT_BOARD) YukkuriLocomotion.detach(child); else child.stopRiding();
        String method = "in_place";
        if (purpose != Purpose.BOAT_BOARD && carrier.level().getBlockCollisions(child, child.getBoundingBox()).iterator().hasNext()) {
            var landing = HatCarry.landing(carrier, child);
            if (landing != null) {
                child.moveTo(landing.x, landing.y, landing.z, carrier.getYRot(), 0);
                method = "landing";
            } else method = "in_place_blocked";
        }
        child.setDeltaMovement(Vec3.ZERO);
        child.fallDistance = 0;
        long now = carrier.level().getGameTime();
        if (holdTicks > 0) holds.put(child.getUUID(), new Hold(now + holdTicks, reason));
        GameplayLog.event(carrier, "family", "carry put down reason={} mode={} purpose={} method={} child={} before={} after={} carriedTicks={} nutrition={} hold={} remaining={}",
                reason, mode, purpose, method, child.getUUID(), before, child.position(), now - since, child.life().nutrition(), holdTicks,
                carrier.getPassengers().size());
        if (!carrying()) {
            purpose = null;
            carrier.family().endCarry();
        }
        return true;
    }

    /** Carrier interruptions and the carried child's own (hurt, burning, command, death) end the ride at the real position. */
    void interrupted(String why, boolean childSide) {
        if (!childSide) { if (carrying()) putDown("carrier_" + why, HOLD_TICKS); return; }
        if (carrier.getVehicle() instanceof YukkuriEntity parent && riding(parent, carrier)) parent.family().carry().putDown(carrier, "child_" + why, HOLD_TICKS);
    }
    public void dropAll(String reason) { if (carrying()) putDown(reason, 0); }

    String describe() {
        var all = riders();
        if (!all.isEmpty()) {
            var ids = new StringBuilder();
            for (var child : all) ids.append(ids.length() == 0 ? "" : ",").append(child.getUUID());
            return "carrying count=" + all.size() + " children=" + ids + " mode=" + mode(carrier, all.get(0)) + " purpose=" + purpose + " ticks="
                    + (carrier.level().getGameTime() - since) + " landed=" + carrier.settlement().migrationLanded();
        }
        if (carrier.getVehicle() instanceof YukkuriEntity parent && riding(parent, carrier))
            return "carried_by=" + parent.getUUID() + " mode=" + mode(parent, carrier);
        return "none holds=" + holds.size();
    }
}
