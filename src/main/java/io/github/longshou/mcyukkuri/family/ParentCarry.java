package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
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
 * Marisa, tail for Chen, mouth for everyone else, one child at a time. Pick-up needs reach, capacity and a free carrier;
 * the child rides until it is put down at its own position (never copied, never teleported to a goal) for a real reason.
 * Client code reads only {@link #mode} / {@link #rawSeat} / {@link #seatYaw}; the finer pose per mode is an art hook.
 */
public final class ParentCarry {
    public enum Mode { HAT, MOUTH, TAIL }
    public enum Purpose { MIGRATE, RETRIEVE, MANUAL }
    enum Step { WORKING, DONE, FAILED }
    /** Ticks the parent stands over the child before it is lifted. */
    static final int LIFT_TICKS = 14;
    /** Pause before the same child is picked up again after an interruption; feeding gets longer. */
    static final int HOLD_TICKS = 100, FEED_HOLD_TICKS = 200;
    /** After the carrier has settled at the destination the child is kept at most this long while it inherits the home. */
    static final int ARRIVE_GRACE_TICKS = 600;
    private static final int MAX_CARRY_TICKS = 12000;
    /** Basic tail seat: top of the tails, behind the body (model units); an art pass refines the pose. */
    private record Tail(double y, double z) {}
    private static final Map<String, Tail> TAILS = Map.of("chen", new Tail(5.3, 10.4), "chen_longtail", new Tail(4.6, 12.0),
            "chen_roundtail", new Tail(4.75, 6.9));
    private record Hold(long until, String reason) {}

    private final YukkuriEntity carrier;
    private final Map<UUID, Hold> holds = new HashMap<>();
    private Purpose purpose;
    private long since, arrivedAt;
    private int lift, homeFailures;
    private UUID lifting;

    ParentCarry(YukkuriEntity carrier) { this.carrier = carrier; }

    // ------------------------------------------------------------------------------------------ shared geometry

    /** How this carrier holds this child; the same answer on both sides, from synchronized size and form only. */
    public static Mode mode(YukkuriView carrier, YukkuriView child) {
        if (carrier == null || child == null) return null;
        if (HatCarry.hasHat(carrier) && carrier.hasVisibleAccessory()
                && HatCarry.fits(carrier.assetId(), child.assetId(), size(child) / size(carrier))) return Mode.HAT;
        return carrier.getVariant() == YukkuriVariant.CHEN ? Mode.TAIL : Mode.MOUTH;
    }
    /** The mode the carrier is using right now, or null when nothing rides on it. */
    public static Mode carryMode(YukkuriView carrier) {
        return carrier.isVehicle() ? mode(carrier, carrier.carriedYukkuri()) : null;
    }
    /** True while the child is a passenger of this carrier. */
    public static boolean visibleCarried(YukkuriView carrier, YukkuriView child) { return carrier.hasPassengerId(child.getUUID()); }
    public static boolean riding(YukkuriEntity carrier, Entity child) { return child instanceof YukkuriEntity && carrier.hasPassenger(child); }
    private static double size(YukkuriView v) { return v.growthScale() * v.getScale(); }

    /** Feet of the carried child before any collision adjustment (also what the client draws for the hat). */
    public static Vec3 rawSeat(YukkuriView carrier, YukkuriView child, Mode mode, float partial) {
        if (mode == Mode.HAT) return HatCarry.seatWorld(carrier, partial);
        float scale = (float) size(carrier);
        float yaw = carrier.level().isClientSide ? Mth.rotLerp(partial, carrier.previousBodyYaw(), carrier.bodyYaw()) : carrier.bodyYaw();
        float turn = (180 - yaw) * Mth.DEG_TO_RAD;
        var base = carrier.level().isClientSide ? carrier.getPosition(partial) : carrier.position();
        if (mode == Mode.TAIL) {
            var tail = TAILS.getOrDefault(carrier.assetId(), TAILS.get("chen"));
            var point = new Vector3f(0, (float) tail.y(), (float) tail.z()).mul(scale / 16).rotateY(turn);
            return base.add(point.x, point.y, point.z);
        }
        var mouth = MouthPlacement.anchor(carrier).mul(scale / 16).rotateY(turn);
        var forward = new Vector3f(0, 0, -1).rotateY(turn);
        double reach = child.getBbWidth() * .25;
        return new Vec3(base.x + mouth.x + forward.x * reach, Math.max(base.y + .03, base.y + mouth.y - child.getBbHeight() * .6),
                base.z + mouth.z + forward.z * reach);
    }
    /** Body yaw (degrees, entity convention) the carried child is drawn with; per-mode offsets belong here. */
    public static float seatYaw(YukkuriView carrier, Mode mode, float partial) {
        // Basic placeholder facing: across the mouth for a mouth-held child, with the carrier otherwise.
        return Mth.rotLerp(partial, carrier.previousBodyYaw(), carrier.bodyYaw()) + (mode == Mode.MOUTH ? 90 : 0);
    }
    /** Mouth carry closes the carrier's mouth on the child. */
    public static boolean mouthCarrying(YukkuriView carrier) { return carryMode(carrier) == Mode.MOUTH; }

    /** Seat used by the passenger position update: the raw seat pulled toward the carrier's own cell until the child fits. */
    public static Vec3 seat(YukkuriEntity carrier, YukkuriEntity child) {
        var mode = mode(carrier, child);
        // The client has already moved the carrier this tick: use its current pose (partial 1) so the child does not trail it.
        var raw = rawSeat(carrier, child, mode, mode == Mode.HAT ? 0 : 1);
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
        if (carrier.profile().aquatic() || carrier.profile().houseBody() || carrier.profile().dangerToYukkuri()) return "carrier_kind";
        if (carrier.isPassenger()) return "carrier_boarded";
        for (var rider : carrier.getPassengers()) if (rider != child) return "occupied";
        if (!ongoing && !carrier.getPassengers().isEmpty()) return "occupied";
        if (child.isVehicle()) return "child_carrying";
        if (child.isPassenger() && child.getVehicle() != carrier) return "child_boarded";
        if (carrier.livePregnancyProgress() > 0) return "pregnant";
        var mode = mode(carrier, child);
        double ratio = volume(child) / Math.max(1e-6, volume(carrier));
        switch (mode) {
            case HAT -> { if (!ongoing && full && !HatCarry.canSeat(carrier, child)) return "hat_seat"; }
            case TAIL -> { if (ratio > .26 * strength(carrier)) return "too_big"; }
            case MOUTH -> {
                if (ratio > .20 * strength(carrier)) return "too_big";
                if (!mouthFree(carrier)) return "mouth_full";
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
                    && (parent.family().carry().capable(child) || parent.getFirstPassenger() == child))
                return true;
        return false;
    }
    /** Migration escorts must be able to carry a child that cannot walk with them. */
    public static boolean mayEscort(YukkuriEntity parent, YukkuriEntity child) {
        return !needsCarry(child) || parent.family().carry().capable(child) || parent.getFirstPassenger() == child;
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
        long mothersCapacity = mother == null ? 0 : Math.min(1, kids.stream().filter(c -> mother.family().carry().capable(c)).count());
        return kids.size() > mothersCapacity;
    }

    // ------------------------------------------------------------------------------------------ pick-up (server)

    public YukkuriEntity carried() { return carrier.getFirstPassenger() instanceof YukkuriEntity y ? y : null; }
    public boolean carrying() { return carried() != null; }
    /** The mouth is taken by a child, so mash feeding and food packing cannot use it. */
    public boolean mouthBusy() { var c = carried(); return c != null && mode(carrier, c) == Mode.MOUTH; }

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
        var home = child.family().carryHome();
        if (home != null && child.distanceToSqr(Vec3.atBottomCenterOf(home)) > 4 * 4) return Purpose.RETRIEVE;
        return null;
    }
    /** A search for this child should end in a lift rather than in waiting for it to walk over. */
    boolean pickupWanted(YukkuriEntity child) { return purposeFor(child) != null && capable(child) && !carrying(); }
    /** During a march a baby near the parent still has to be lifted before the parent walks on. */
    boolean pickupDue(YukkuriEntity child) { return purposeFor(child) == Purpose.MIGRATE && capable(child) && !carrying() && ready(); }

    private boolean inReach(YukkuriEntity child) {
        double dx = child.getX() - carrier.getX(), dz = child.getZ() - carrier.getZ();
        double reach = (carrier.getBbWidth() + child.getBbWidth()) / 2 + .35;
        return dx * dx + dz * dz <= reach * reach && Math.abs(child.getY() - carrier.getY()) < 1.2 && carrier.hasLineOfSight(child);
    }

    /** One tick of walking to the child, standing over it and lifting it. */
    Step tickPickup(YukkuriEntity child, int taskTicks) {
        var why = capacityProblem(carrier, child, false, true);
        if (why != null || !ready()) {
            GameplayLog.limited(carrier, "family", "carry.refused", 400, "carry pickup refused child={} reason={} ready={}", child.getUUID(),
                    why == null ? "carrier_busy" : why, ready());
            return Step.FAILED;
        }
        if (!inReach(child)) {
            lift = 0;
            lifting = null;
            if (taskTicks % 10 == 1 && !carrier.life().navigate(child.blockPosition(), 1.1, false)) return Step.FAILED;
            return Step.WORKING;
        }
        carrier.getNavigation().stop();
        carrier.getLookControl().setLookAt(child, 30, 30);
        if (child.life().mealTicks() > 0 || child.isPanicking() || child.isInWaterOrBubble() || child.operations().active()) return Step.WORKING;
        if (!child.getUUID().equals(lifting)) {
            lifting = child.getUUID();
            lift = 0;
            GameplayLog.event(carrier, "family", "carry lift started mode={} purpose={} child={} childPos={} carrierPos={} nutrition={}",
                    mode(carrier, child), purposeFor(child), child.getUUID(), child.blockPosition().toShortString(),
                    carrier.blockPosition().toShortString(), child.life().nutrition());
        }
        if (++lift < LIFT_TICKS) return Step.WORKING;
        return board(child) ? Step.DONE : Step.FAILED;
    }

    private boolean board(YukkuriEntity child) {
        var intent = purposeFor(child);
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
        purpose = intent == null ? Purpose.RETRIEVE : intent;
        since = carrier.level().getGameTime();
        arrivedAt = 0;
        homeFailures = 0;
        holds.remove(child.getUUID());
        GameplayLog.event(carrier, "family", "carry pickup mode={} purpose={} child={} childStage={} before={} seat={} nutrition={} capacity={}/{}",
                mode, purpose, child.getUUID(), child.family().stage(), before, child.position(), child.life().nutrition(),
                String.format("%.3f", volume(child)), String.format("%.3f", volume(carrier)));
        carrier.showReaction("happy", "gentle", 40);
        return true;
    }

    // ------------------------------------------------------------------------------------------ carrying (server)

    /** Per tick on the carrier: the one place that decides whether the child still rides. */
    void tick() {
        var child = carried();
        if (child == null) {
            if (purpose != null) purpose = null;
            return;
        }
        long now = carrier.level().getGameTime();
        if (purpose == null) adopt(child, now);
        String why = releaseReason(child, now);
        if (why != null) putDown(why, why.equals("feeding") ? FEED_HOLD_TICKS : HOLD_TICKS);
        else if (purpose == Purpose.RETRIEVE) carrier.family().beginCarry();
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

    private String releaseReason(YukkuriEntity child, long now) {
        if (!child.isAlive()) return "child_died";
        if (!carrier.isAlive()) return "carrier_died";
        var problem = capacityProblem(carrier, child, true, false);
        if (problem != null) return problem;
        if (carrier.isInWaterOrBubble() || child.isInWaterOrBubble()) return "water";
        var life = carrier.life();
        if (carrier.isOnFire()) return "carrier_burning";
        if (carrier.isPanicking()) return "carrier_panic";
        if (purpose != Purpose.MANUAL) {
            if (life.isSleeping()) return "carrier_sleeping";
            if (life.mealTicks() > 0 && mode(carrier, child) == Mode.MOUTH) return "carrier_eating";
            if (life.hasUrgentNeed() && !carrier.settlement().marchTiredOnly()) return "carrier_need";
        }
        if (carrier.operations().active() || child.operations().active()) return "operation";
        if (now - since > MAX_CARRY_TICKS) return "timeout";
        if (now % 5 == 0 && mode(carrier, child) != Mode.HAT && seat(carrier, child) == null) return "seat_blocked";
        if (purpose != Purpose.MANUAL && child.life().nutrition() < 30 && carrier.family().feeding().hasCarriedOpportunity(child)) return "feeding";
        if (purpose == Purpose.MIGRATE) {
            boolean landed = carrier.settlement().migrationLanded();
            if (landed && arrivedAt == 0) {
                arrivedAt = now;
                GameplayLog.event(carrier, "family", "carry destination reached child={} childSettled={}", child.getUUID(), child.settlement().migrationLanded());
            }
            if (landed) {
                // The baby inherits the home only beside the parent who holds it: put it down once the child has it, or the
                // parent stands at her own bed (the census then finds them together), or the wait has run out.
                if (child.settlement().migrationLanded()) return "arrived_destination";
                var stand = carrier.life().homeStand();
                if (stand == null || carrier.distanceToSqr(Vec3.atBottomCenterOf(stand)) <= 9) return "arrived_home";
                if (now - arrivedAt > ARRIVE_GRACE_TICKS) return "arrival_timeout";
            }
            if (!landed && !carrier.settlement().familyMarching()) return "migration_ended";
        }
        return null;
    }

    /** Walk home with the child (retrieval); false once the walk is over. */
    boolean tickHome(int taskTicks) {
        var child = carried();
        if (child == null) return false;
        var home = child.family().carryHome();
        if (home == null) { putDown("no_home", HOLD_TICKS); return false; }
        if (carrier.distanceToSqr(Vec3.atBottomCenterOf(home)) <= 2.5 * 2.5) { putDown("arrived_home", 0); return false; }
        if (taskTicks % 10 == 1 && !carrier.life().navigate(home, 1.0, false)) {
            if (++homeFailures >= 3) { putDown("home_unreachable", HOLD_TICKS); return false; }
        }
        return true;
    }

    /** Set the child down where it is (clearing it from blocks only if needed). Returns false when nothing was carried. */
    public boolean putDown(String reason, int holdTicks) {
        var child = carried();
        if (child == null || !(carrier.level() instanceof ServerLevel)) return false;
        var mode = mode(carrier, child);
        Vec3 before = child.position();
        child.stopRiding();
        String method = "in_place";
        if (carrier.level().getBlockCollisions(child, child.getBoundingBox()).iterator().hasNext()) {
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
        GameplayLog.event(carrier, "family", "carry put down reason={} mode={} purpose={} method={} child={} before={} after={} carriedTicks={} nutrition={} hold={}",
                reason, mode, purpose, method, child.getUUID(), before, child.position(), now - since, child.life().nutrition(), holdTicks);
        purpose = null;
        carrier.family().endCarry();
        return true;
    }

    /** Carrier interruptions and the carried child's own (hurt, burning, command, death) end the ride at the real position. */
    void interrupted(String why, boolean childSide) {
        if (!childSide) { if (carrying()) putDown("carrier_" + why, HOLD_TICKS); return; }
        if (carrier.getVehicle() instanceof YukkuriEntity parent && riding(parent, carrier)) parent.family().carry().putDown("child_" + why, HOLD_TICKS);
    }
    public void dropAll(String reason) { if (carrying()) putDown(reason, 0); }

    String describe() {
        var child = carried();
        if (child != null) return "carrying child=" + child.getUUID() + " mode=" + mode(carrier, child) + " purpose=" + purpose + " ticks="
                + (carrier.level().getGameTime() - since) + " landed=" + carrier.settlement().migrationLanded();
        if (carrier.getVehicle() instanceof YukkuriEntity parent && riding(parent, carrier))
            return "carried_by=" + parent.getUUID() + " mode=" + mode(parent, carrier);
        return "none holds=" + holds.size();
    }
}
