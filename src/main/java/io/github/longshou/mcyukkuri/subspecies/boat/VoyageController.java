package io.github.longshou.mcyukkuri.subspecies.boat;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatDock;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The captain's one lifecycle, as an explicit stored phase machine: {@code ASHORE -> PREPARE -> MUSTER -> MOOR -> LOAD -> EMBARK ->
 * AFLOAT -> RETURN -> DOCK -> DISEMBARK -> STOW -> ASHORE}, plus {@code CAPSIZED} and {@code RECOVER}. It owns the launch (the
 * young go aboard first, the captain last), the landing (a mirror of the launch, the captain last off), the failure branches and
 * every time limit. Domains that want the captain ashore call {@link #requestShore} and nothing else.
 *
 * <p>Each phase has a limit ({@link VoyagePhase#ttl}) and a stated fallback: a failed launch stows everything back to ASHORE and
 * cools down; a failed landing tries another berth (at most two) and then stays AFLOAT with the area marked unavailable for a
 * while, which is what keeps a refused shore request from being retried every tick. A child that cannot be landed only changes
 * its drop point or stays aboard; it never cancels the landing of the others.
 *
 * <p>Transactional phases are ticked by the priority-3 goal of {@link AquaticLife}; {@code AFLOAT} belongs to the
 * {@link AfloatAgenda}. {@link #clock} runs every tick regardless of goals and only keeps the stored phase honest (time limits,
 * what the captain actually rides, a beached hull with the captain ashore).
 */
public final class VoyageController {
    public enum Answer { ACCEPTED, COOLDOWN, NO_BERTH, BUSY }

    private static final int BUSY_LIMIT = 300, CHILD_LOAD_TICKS = 420, CHILD_UNLOAD_TICKS = 420, MAX_RESPOTS = 6, BERTH_ATTEMPTS = 2,
            UNAVAILABLE_TICKS = 2400, RECOVER_BLOCK_TICKS = 1200, SHORE_NEAR_SQUARED = 36, NO_BERTH_CACHE = 100;

    private final YukkuriEntity entity;
    private final AquaticLife life;

    // ---- stored
    private VoyagePhase phase = VoyagePhase.ASHORE;
    private long since;
    private int attempt;
    private BlockPos bank, water;
    private UUID hullId;
    private int cooldown;
    private int pendingCooldown;
    private boolean birth;
    private String lastRefusal = "none", shoreReason = "none";
    private long shoreUntil, recoverBlockedUntil;
    private boolean courtship;

    // ---- not stored
    private ShoreBerth.Berth berth;
    private long nextPlan, noBerthUntil, beachedSince, stageSince, nextRoute;
    private final Map<Long, Long> shoreUnavailable = new HashMap<>();
    private final Map<UUID, String> manifest = new LinkedHashMap<>();
    private final Map<UUID, Integer> tries = new HashMap<>();
    private final Map<UUID, Long> childSince = new HashMap<>();
    private final Set<UUID> kept = new HashSet<>();
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private final Map<UUID, Vec3> spots = new HashMap<>();
    private final List<AABB> used = new ArrayList<>();
    private UUID current;
    private boolean queued, aborted, hatStage;
    private int blocked, replans, respots, refusals, routeFails, prepareTicks, provisionTicks, familyWait, hullWait;
    private long airSince, actorSince, musterBusySince;
    private String lastMusterLog = "";
    private boolean moorTried;
    private long captainSince, ashoreSince;
    private Vec3 captainSpot;

    public VoyageController(YukkuriEntity entity, AquaticLife life) { this.entity = entity; this.life = life; }

    private ServerLevel level() { return (ServerLevel) entity.level(); }
    private long now() { return level().getGameTime(); }

    // ================================================================= reads
    public VoyagePhase phase() { return phase; }
    public long since() { return since; }
    public int attempt() { return attempt; }
    public int cooldown() { return cooldown; }
    public boolean landing() { return phase.landing(); }
    public boolean shoreVisit() { return phase.shoreVisit(); }
    public BlockPos bank() { return bank; }
    public String lastRefusal() { return lastRefusal; }
    /** The child is part of the trip being loaded (it walks to the bank rather than wandering). */
    public boolean awaiting(UUID child) { return phase.launching() && manifest.containsKey(child) && !"ABSENT".equals(manifest.get(child)); }

    /** One line for the {@code water.launch} readout. */
    public String detail() {
        long now = now();
        return "phase=" + phase + " since=" + (now - since) + " attempt=" + attempt + " lastRefusal=" + lastRefusal
                + " cooldown=" + cooldown + " bank=" + (bank == null ? "none" : bank.toShortString())
                + " water=" + (water == null ? "none" : water.toShortString()) + " hull=" + hullId
                + " shoreReason=" + shoreReason + " shoreUntil=" + Math.max(0, shoreUntil - now)
                + " unavailable=" + unavailableLeft(entity.blockPosition()) + " muster=" + manifest.size() + manifest.values();
    }

    // ================================================================= phase changes
    private void enter(VoyagePhase next, String reason) {
        if (next == phase) return;
        var before = phase;
        phase = next;
        since = now();
        stageSince = since;
        blocked = replans = respots = refusals = routeFails = 0;
        current = null;
        airSince = actorSince = 0;
        moorTried = false; captainSince = 0; ashoreSince = 0; captainSpot = null;
        queue.clear(); queued = false; kept.clear(); spots.clear(); used.clear();
        if (next == VoyagePhase.STOW) hatStage = false;
        if (before == VoyagePhase.AFLOAT) life.agenda().halt("phase_" + next);
        GameplayLog.event(entity, "aquatic", "voyage phase {} -> {} reason={} attempt={} hull={} bank={} water={} cooldown={}",
                before, next, reason, attempt, hullId, bank, water, cooldown);
        var boat = hull();
        if (boat != null) boat.syncVoyage(phase.ordinal());
    }

    private HatBoatEntity hull() {
        return hullId != null && level().getEntity(hullId) instanceof HatBoatEntity h && !h.isRemoved() ? h : null;
    }
    private HatBoatEntity ridden() {
        return entity.getVehicle() instanceof HatBoatEntity boat && entity.getUUID().equals(boat.ownerYukkuri()) ? boat : null;
    }

    /** Back to the bank with nothing under way. The cooldown keeps a refused launch from restarting at once. */
    private void toAshore(String reason, int cool) {
        cooldown = Math.max(cooldown, cool);
        enter(VoyagePhase.ASHORE, reason);
        life.voyageEnded();
        manifest.clear(); tries.clear(); childSince.clear(); berth = null; birth = false;
        bank = water = null;
        aborted = false; pendingCooldown = 0;
    }

    /** A launch gave up before leaving: say why, cool down, stow everything back. */
    private void cancelTrip(String reason) {
        GameplayLog.event(entity, "aquatic", "voyage cancelled reason={} phase={} attempt={} hull={} manifest={}", reason, phase, attempt, hullId, manifest);
        lastRefusal = reason;
        var boat = hull();
        if (boat != null && phase.ordinal() >= VoyagePhase.MOOR.ordinal() && phase.ordinal() <= VoyagePhase.EMBARK.ordinal()) {
            // The hull is already down: its young go back onto the bank and the hat is taken in again.
            aborted = true;
            pendingCooldown = 400;
            enter(VoyagePhase.DISEMBARK, "launch_cancelled");
            return;
        }
        toAshore("cancelled:" + reason, 400);
    }

    // ================================================================= every tick, independent of goals
    public void clock() {
        if (entity.level().isClientSide) return;
        if (cooldown > 0) cooldown--;
        reconcile();
        var rider = ridden();
        if (rider != null) {
            // A trip ends while the captain is busy with something that takes her hands, her attention or the whole hull.
            if (rider.helm().travelling() && (entity.isCommandPlaying() || entity.operations().active() || entity.family().crew().busy()
                    || entity.family().flying() || rider.crowdContesting() || rider.landing()
                    || entity.isPanicking() && !life.familyLife().alarm().active() || entity.life().isSleeping()))
                rider.helm().cancelTravel("captain_busy");
        } else if (phase == VoyagePhase.ASHORE && entity.tickCount % 20 == 0 && entity.isAlive() && !entity.isPassenger()
                && entity.life().custody().boat() != null && level().getEntity(entity.life().custody().boat()) instanceof HatBoatEntity hat
                && hat.isCradle() && hat.ownsPhysicalHat(entity) && !entity.family().keepsCradle() && entity.distanceToSqr(hat) <= 48 * 48)
            // The set-down cradle is no longer needed: its owner comes back for it.
            entity.family().fetchHat(hat);
        if (phase.transactional() && phase.ttl() > 0 && now() - since > phase.ttl()) fail("ttl");
        var boat = hull() != null ? hull() : ridden();
        if (boat != null && entity.getUUID().equals(boat.ownerYukkuri())) boat.syncVoyage(phase.ordinal());
    }

    /** Keep the stored phase consistent with what the captain actually rides. */
    private void reconcile() {
        long now = now();
        var aboard = ridden();
        if (aboard != null) {
            hullId = aboard.getUUID();
            if (aboard.state() == HatBoatEntity.State.CAPSIZED) {
                if (phase != VoyagePhase.CAPSIZED) enter(VoyagePhase.CAPSIZED, "hull_capsized");
                return;
            }
            switch (phase) {
                case ASHORE, PREPARE, MUSTER, RECOVER, MOOR, LOAD, EMBARK, CAPSIZED -> {
                    if (phase == VoyagePhase.CAPSIZED && (!aboard.isAfloat() || aboard.unsteadyMotion())) return;
                    GameplayLog.event(entity, "aquatic", "voyage aboard hat={} from={} afloat={}", aboard.getUUID(), phase, aboard.isAfloat());
                    attempt = 0; manifest.clear();
                    enter(VoyagePhase.AFLOAT, "aboard_" + phase.name().toLowerCase(Locale.ROOT));
                    aboard.helm().release(entity.getUUID());
                }
                case AFLOAT -> {
                    // A grounded hull with its captain aboard goes to the nearest berth.
                    if (aboard.state() == HatBoatEntity.State.BEACHED && !aboard.isAfloat()) {
                        if (beachedSince == 0) beachedSince = now;
                        else if (now - beachedSince >= 40 && now >= nextPlan) {
                            nextPlan = now + 200;
                            beginShoreVisit("beached", now + 1200, true);
                        }
                    } else beachedSince = 0;
                }
                default -> {}
            }
            return;
        }
        beachedSince = 0;
        switch (phase) {
            case AFLOAT, RETURN, DOCK -> {
                var boat = hull();
                if (entity.isInWaterOrBubble() || boat != null && boat.state() == HatBoatEntity.State.CAPSIZED) enter(VoyagePhase.CAPSIZED, "left_hull_in_water");
                else if (entity.onGround() || entity.isPassenger()) {
                    if (entity.isPassenger()) return;
                    enter(VoyagePhase.ASHORE, "left_hull");
                    life.voyageEnded();
                }
            }
            case CAPSIZED -> {
                if (!entity.isInWaterOrBubble() && entity.onGround() && !entity.isPassenger()) enter(VoyagePhase.ASHORE, "ashore_after_capsize");
            }
            case ASHORE -> {
                if (entity.tickCount % 20 == 3 && now >= recoverBlockedUntil && life.selfRescue().recoverableHat() != null && !life.firstVoyage().active()) {
                    hullId = life.selfRescue().recoverableHat().getUUID();
                    enter(VoyagePhase.RECOVER, "own_hull_in_reach");
                }
            }
            default -> {}
        }
    }

    /** The fallback of each phase whose time or conditions ran out. */
    private void fail(String reason) {
        GameplayLog.event(entity, "aquatic", "voyage phase failed phase={} reason={} attempt={} elapsed={}", phase, reason, attempt, now() - since);
        switch (phase) {
            case PREPARE -> toAshore("prepare_" + reason, 40);
            case MUSTER -> cancelTrip("muster_" + reason);
            case MOOR, LOAD, EMBARK -> cancelTrip(phase.name().toLowerCase(Locale.ROOT) + "_" + reason);
            case RETURN, DOCK -> fallbackLanding(phase.name().toLowerCase(Locale.ROOT) + "_" + reason);
            case DISEMBARK -> {
                if (ridden() != null) fallbackLanding("disembark_" + reason);
                else giveUpUnloading("disembark_" + reason);
            }
            case STOW -> stowFailed("stow_" + reason);
            case RECOVER -> { recoverBlockedUntil = now() + RECOVER_BLOCK_TICKS; toAshore("recover_" + reason, 0); }
            case CAPSIZED -> toAshore("capsized_" + reason, 0);
            default -> {}
        }
    }

    // ================================================================= requests
    /**
     * A domain wants the captain ashore. Honours the area cooldown (a landing that failed or found no berth is not retried at
     * once), so a request repeated every second cannot loop the family between sailing and docking.
     */
    public Answer requestShore(String reason, long until) {
        if (entity.level().isClientSide) return Answer.BUSY;
        long now = now();
        var boat = ridden();
        if (boat == null) return answer(Answer.BUSY, "not_aboard");
        if (phase.shoreVisit()) { shoreReason = reason; shoreUntil = Math.max(shoreUntil, until); return Answer.ACCEPTED; }
        if (phase != VoyagePhase.AFLOAT) return answer(Answer.BUSY, "phase_" + phase);
        if (!boat.isAfloat() || boat.unsteadyMotion() || entity.family().flying() || entity.family().crew().busy()
                || life.familyLife().alarm().preventsOuting() || life.firstVoyage().active())
            return answer(Answer.BUSY, "captain_or_hull_busy");
        long left = unavailableLeft(boat.blockPosition());
        if (left > 0) return answer(Answer.COOLDOWN, "area_unavailable:" + left);
        if (now < noBerthUntil) return Answer.NO_BERTH;
        return beginShoreVisit(reason, until, false);
    }

    private Answer answer(Answer answer, String why) {
        lastRefusal = answer + ":" + why;
        GameplayLog.limited(entity, "aquatic", "shore.request." + answer + "." + why, 400, "shore request refused answer={} why={} phase={}", answer, why, phase);
        return answer;
    }

    private Answer beginShoreVisit(String reason, long until, boolean beached) {
        var boat = ridden();
        if (boat == null) return Answer.BUSY;
        var outcome = berthFor(boat, null);
        if (!(outcome instanceof ShoreBerth.Berth found)) {
            noBerthUntil = now() + NO_BERTH_CACHE;
            lastRefusal = "NO_BERTH:" + ((ShoreBerth.Refusal) outcome).describe();
            GameplayLog.limited(entity, "aquatic", "shore.noberth", 400, "shore request found no berth reason={} detail={}", reason, ((ShoreBerth.Refusal) outcome).describe());
            return Answer.NO_BERTH;
        }
        berth = found; bank = found.bank(); water = found.water();
        shoreReason = reason; shoreUntil = until; courtship = "courtship".equals(reason); attempt = 0;
        life.rememberBank(bank);
        entity.life().wakeInHat();
        GameplayLog.event(entity, "aquatic", "shore visit accepted reason={} bank={} water={} beached={} passengers={} cargo={} paddle={}",
                reason, bank, water, beached, boat.getPassengers().size(), boat.cargo().count(), boat.cargo().paddle());
        enter(beached ? VoyagePhase.DOCK : VoyagePhase.RETURN, reason);
        return Answer.ACCEPTED;
    }

    private ShoreBerth.Outcome berthFor(HatBoatEntity boat, java.util.function.Predicate<BlockPos> acceptWater) {
        int crew = Math.max(0, boat.getPassengers().size() - 1);
        return ShoreBerth.find(level(), new ShoreBerth.Query(entity, boat, boat.hullScale(), boat.blockPosition(), ShoreBerth.Purpose.LAND,
                life.boardingBank(), ShoreBerth.defaultRange(ShoreBerth.Purpose.LAND), crew, acceptWater == null ? p -> true : acceptWater));
    }

    private long areaKey(BlockPos pos) { return ((long) (pos.getX() >> 5) << 32) ^ (pos.getZ() >> 5 & 0xffffffffL); }
    private long unavailableLeft(BlockPos pos) {
        var until = shoreUnavailable.get(areaKey(pos));
        return until == null ? 0 : Math.max(0, until - now());
    }
    private void markUnavailable(BlockPos pos, String why) {
        long now = now();
        shoreUnavailable.values().removeIf(until -> until <= now);
        if (shoreUnavailable.size() > 16) shoreUnavailable.clear();
        shoreUnavailable.put(areaKey(pos), now + UNAVAILABLE_TICKS);
        GameplayLog.event(entity, "aquatic", "shore unavailable area={} for={} why={}", areaKey(pos), UNAVAILABLE_TICKS, why);
    }

    // ================================================================= ASHORE: planning a launch
    /** True when the captain starts a launch now (the PREPARE phase begins). */
    public boolean wantsLaunch() {
        if (phase != VoyagePhase.ASHORE) return false;
        long now = now();
        boolean wantsBirth = life.birthLaunchEligible();
        String refusal = wantsBirth ? (cooldown > 0 ? "cooldown" : null) : life.launchRefusal(cooldown);
        if (refusal != null || now < nextPlan) return false;
        nextPlan = now + 40;
        var outcome = life.planLaunch(wantsBirth);
        if (!(outcome instanceof ShoreBerth.Berth found)) {
            lastRefusal = "NO_BERTH:" + ((ShoreBerth.Refusal) outcome).describe();
            life.launchCheck("no_berth:" + ((ShoreBerth.Refusal) outcome).describe());
            return false;
        }
        bank = found.bank(); water = found.water(); berth = null; birth = wantsBirth; attempt = 0;
        prepareTicks = provisionTicks = familyWait = 0;
        life.launchCheck("bank_selected");
        life.rememberBank(bank);
        GameplayLog.event(entity, "aquatic", "voyage planned bank={} water={} birth={} paddle={} provisions={}", bank, water, birth, life.hasStick(), life.provisions());
        enter(VoyagePhase.PREPARE, birth ? "birth_launch" : "planned");
        return true;
    }

    // ================================================================= transactional phases (priority-3 goal)
    /** Whether the captain can still carry out a transaction at all (not dead, commanded, burning or in the water). */
    public boolean capable() { return life.availableForVoyage(); }

    /** One tick of the current transactional phase; the behaviour to show. */
    public String tick() {
        return switch (phase) {
            case PREPARE -> prepare();
            case MUSTER -> muster();
            case MOOR -> moor();
            case LOAD -> load();
            case EMBARK -> embark();
            case RETURN -> sailBack();
            case DOCK -> dock();
            case DISEMBARK -> disembark();
            case STOW -> stow();
            case RECOVER -> recover();
            default -> Behaviors.IDLE;
        };
    }

    // ---- PREPARE
    private String prepare() {
        long elapsed = now() - since;
        String abort = life.launchAbortReason(birth);
        if (abort != null) { toAshore("prepare_" + abort, 40); return Behaviors.IDLE; }
        if (water == null || bank == null || !calmWater(water) || !entity.life().dryStand(bank)) { toAshore("prepare_water_or_bank_changed", 40); return Behaviors.IDLE; }
        if (!life.hasStick() && elapsed < 120 && !life.shore().tired()) { life.seekStick((int) elapsed); return Behaviors.BOAT_FIND_PADDLE; }
        if (!life.shore().tired() && provisionTicks < 120 && life.provisions() < Math.min(4, 1 + life.nearbyYoung().size()) && life.prepareFood((int) elapsed)) {
            provisionTicks++; return Behaviors.BOAT_PROVISION;
        }
        var stand = Vec3.atBottomCenterOf(bank);
        if (!SurfaceApproach.arrived(entity, stand)) {
            if (entity.distanceToSqr(stand) > 2.25 && elapsed % 20 == 1 && !entity.life().navigate(bank, .9, false)) {
                toAshore("prepare_bank_navigation_failed", 200); return Behaviors.IDLE;
            }
            SurfaceApproach.walk(entity, stand, .9);
            return Behaviors.BOAT_SEEK_BANK;
        }
        SurfaceApproach.halt(entity);
        if (life.firstVoyage().needsLesson()) {
            // A novice may collect its paddle, but cannot bypass the supervised launch by ordinary wandering.
            if (++familyWait > 200) toAshore("prepare_needs_lesson", 200);
            return Behaviors.VOYAGE_WAIT_PARENT;
        }
        manifest.clear(); tries.clear(); childSince.clear();
        for (var young : life.nearbyYoung()) manifest.put(young.getUUID(), "?");
        musterBusySince = 0;
        enter(VoyagePhase.MUSTER, "at_bank");
        return Behaviors.BOAT_WAIT_YOUNG;
    }

    private boolean calmWater(BlockPos pos) { return io.github.longshou.mcyukkuri.subspecies.HatBoatRoute.calmSurface(level(), pos); }

    // ---- MUSTER
    private YukkuriEntity child(UUID id) { return level().getEntity(id) instanceof YukkuriEntity y ? y : null; }

    /** READY (a jumper at the bank), CARRY (a dependent at the bank), BUSY(reason) or ABSENT. */
    private String classify(YukkuriEntity young) {
        if (young == null || !young.isAlive() || young.isRemoved()) return "ABSENT";
        if (young.aquaticLife().firstVoyage().independent() || young.isPassenger() || bank == null || young.distanceToSqr(Vec3.atBottomCenterOf(bank)) > 24 * 24) return "ABSENT";
        if (young.isNoAi() || young.isCommandPlaying() || young.operations().active()) return "BUSY(task)";
        if (young.family().flying() || young.family().crew().busy()) return "BUSY(transfer)";
        if (young.isInWaterOrBubble()) return "BUSY(in_water)";
        if (young.isPanicking()) return "BUSY(panicking)";
        if (young.life().mealTicks() > 0) return "BUSY(eating)";
        if (young.life().isSleeping()) return "BUSY(sleeping)";
        if (young.life().nutrition() < 30 || young.life().hasUrgentNeed()) return "BUSY(needs)";
        if (young.distanceToSqr(Vec3.atBottomCenterOf(bank)) > 2.5 * 2.5) return "BUSY(away)";
        return young.childhood().canJump() && young.family().stage() != io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY ? "READY" : "CARRY";
    }

    private String muster() {
        long now = now();
        if (now % 5 != 0 && !manifest.containsValue("?")) return Behaviors.BOAT_WAIT_YOUNG;
        String firstBusy = null;
        for (var id : new ArrayList<>(manifest.keySet())) {
            var young = child(id);
            var status = classify(young);
            manifest.put(id, status);
            if (status.startsWith("BUSY")) {
                if (firstBusy == null) firstBusy = status;
                if (now % 20 == 0 && young != null) life.gatherAtBank(young, bank);
            }
        }
        String summary = manifest.values().toString();
        if (!summary.equals(lastMusterLog)) {
            lastMusterLog = summary;
            GameplayLog.limited(entity, "aquatic", "muster", 100, "muster {} ", manifest);
        }
        if (firstBusy == null) { enter(VoyagePhase.MOOR, "mustered"); return Behaviors.BOAT_LAUNCH; }
        if (musterBusySince == 0) musterBusySince = now;
        if (now - musterBusySince > BUSY_LIMIT) { cancelTrip("young_busy:" + firstBusy); return Behaviors.IDLE; }
        if (now % 100 == 1) entity.speak("wait_family");
        // Tend the busy: a hungry one is not left on the bank, a sleeping one is waited for within the limit.
        return Behaviors.BOAT_WAIT_YOUNG;
    }

    // ---- MOOR
    private String moor() {
        var boat = hull();
        if (boat == null) {
            if (moorTried) { cancelTrip("hull_missing"); return Behaviors.IDLE; }
            moorTried = true;
            var made = HullFactory.moorAtBank(entity, bank, water);
            if (made instanceof HullFactory.Refused refused) {
                lastRefusal = "hull_refused:" + refused.describe();
                cancelTrip("hull_refused:" + refused.reason());
                return Behaviors.IDLE;
            }
            boat = HullFactory.boatOf(made);
            hullId = boat.getUUID();
            boat.syncVoyage(phase.ordinal());
        }
        if (berth == null) {
            var outcome = ShoreBerth.berthAt(level(), entity, boat.hullScale(), bank, water, ShoreBerth.Purpose.LAUNCH);
            if (!(outcome instanceof ShoreBerth.Berth found)) { cancelTrip("berth_lost"); return Behaviors.IDLE; }
            berth = found;
        }
        var status = boat.helm().berth(entity, Purpose.MOORING, berth.center(), berth.yaw(), .06, 4f);
        if (status.arrived() || status.blocked() && boat.position().distanceToSqr(berth.center()) < .04) { enter(VoyagePhase.LOAD, "moored"); return Behaviors.BOAT_LAUNCH; }
        return Behaviors.BOAT_LAUNCH;
    }

    // ---- LOAD
    private String load() {
        var boat = hull();
        long now = now();
        if (boat == null || !boat.isAfloat()) { cancelTrip("hull_lost"); return Behaviors.IDLE; }
        if (berth != null) boat.helm().berth(entity, Purpose.MOORING, berth.center(), berth.yaw(), .06, 4f);
        if (boat.unsteadyMotion()) { if (++hullWait > 120) cancelTrip("hull_unsteady"); return Behaviors.BOAT_STEADY; }
        hullWait = 0;
        if (entity.family().crew().active() || boat.crew().busy()) return Behaviors.BOAT_LAUNCH;
        // Jumpers first, dependents second; whoever is not yet aboard.
        YukkuriEntity next = null;
        boolean anyLeft = false;
        for (var id : manifest.keySet()) {
            var young = child(id);
            if (young == null || !young.isAlive() || young.getVehicle() == boat) continue;
            var status = classify(young);
            if (status.equals("ABSENT")) continue;
            anyLeft = true;
            if (status.startsWith("BUSY")) {
                long first = childSince.computeIfAbsent(id, k -> now);
                if (now - first > CHILD_LOAD_TICKS) { cancelTrip("young_unloadable:" + status); return Behaviors.IDLE; }
                if (status.equals("BUSY(away)") && now % 20 == 0) life.gatherAtBank(young, bank);
                continue;
            }
            if (next == null || status.equals("READY") && !classify(next).equals("READY")) next = young;
        }
        if (!anyLeft) { enter(VoyagePhase.EMBARK, "young_aboard"); return Behaviors.BOAT_LAUNCH; }
        if (next == null) return Behaviors.BOAT_WAIT_YOUNG;
        var id = next.getUUID();
        long first = childSince.computeIfAbsent(id, k -> now);
        if (now - first > CHILD_LOAD_TICKS) { cancelTrip("young_unloadable:load_timeout"); return Behaviors.IDLE; }
        if (now % 10 != 0) return Behaviors.BOAT_LAUNCH;
        if (!boat.makeRoom(next, false)) {
            GameplayLog.limited(entity, "aquatic", "load.no_room", 200, "load child={} reason=no_room hat={} {}", id, boat.getUUID(), boat.roomDebug(next));
            if (tries.merge(id, 1, Integer::sum) > 8) cancelTrip("young_unloadable:no_room");
            return Behaviors.BOAT_LAUNCH;
        }
        var result = next.childhood().canJump() && next.family().stage() != io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY
                ? CrewTransfers.boardFromShore(next, boat, false) : CrewTransfers.loadFromBank(next, entity, boat);
        if (result.ok()) {
            GameplayLog.limited(entity, "aquatic", "load.start", 100, "load child={} mode={} hat={}", id, result.ticket().mode(), boat.getUUID());
            return Behaviors.BOAT_LAUNCH;
        }
        int n = tries.merge(id, 1, Integer::sum);
        GameplayLog.limited(entity, "aquatic", "load.refused", 200, "load child={} refused class={} reason={} try={}", id, result.refusal(), result.reason(), n);
        // GEOMETRY is deterministic: the child is brought to another cell beside the captain before the next try.
        if (result.refusal() == CrewTransfer.Refusal.GEOMETRY && n % 2 == 0) life.gatherAtBank(next, bank);
        if (n > 12 || result.refusal() == CrewTransfer.Refusal.INCAPABLE && n > 4) cancelTrip("young_unloadable:" + result.reason());
        return Behaviors.BOAT_LAUNCH;
    }

    // ---- EMBARK
    private String embark() {
        var boat = hull();
        if (boat == null || !boat.isAfloat()) { cancelTrip("hull_lost"); return Behaviors.IDLE; }
        if (entity.getVehicle() == boat) { reconcile(); return Behaviors.BOAT_LAUNCH; }
        if (berth != null) boat.helm().berth(entity, Purpose.MOORING, berth.center(), berth.yaw(), .06, 4f);
        entity.getNavigation().stop();
        if (!boat.unsteadyMotion() && !entity.family().crew().active() && (now() - since) % 10 == 1) CrewTransfers.embark(entity, boat);
        return Behaviors.BOAT_LAUNCH;
    }

    // ---- RETURN
    private String sailBack() {
        var boat = ridden();
        long now = now();
        if (boat == null) return Behaviors.IDLE;
        if (now > shoreUntil + 600 && now - since > 200) { backToAfloat("request_expired", false); return Behaviors.IDLE; }
        if (life.familyLife().alarm().preventsOuting() || entity.family().crew().busy() || boat.unsteadyMotion()) {
            boat.helm().hold(entity, Purpose.SAFETY);
            return Behaviors.BOAT_STEADY;
        }
        if (berth == null && !refreshBerth(boat)) return Behaviors.BOAT_CALL;
        double near = boat.position().subtract(berth.center()).horizontalDistanceSqr();
        if (near <= SHORE_NEAR_SQUARED) { enter(VoyagePhase.DOCK, "near_berth"); return Behaviors.BOAT_LAND; }
        if ((now - since) % 40 == 1) {
            if (boat.helm().travel(entity, Purpose.LANDING, berth.center())) routeFails = 0;
            else if (++routeFails > 6) { fallbackLanding("no_route"); return Behaviors.BOAT_CALL; }
        }
        if (!boat.paddling()) { if ((now - since) % 120 == 1) entity.speak("wait_family"); return Behaviors.BOAT_CALL; }
        return Behaviors.BOAT_LAND;
    }

    private boolean refreshBerth(HatBoatEntity boat) {
        var outcome = bank != null && water != null
                ? ShoreBerth.berthAt(level(), entity, boat.hullScale(), bank, water, ShoreBerth.Purpose.LAND) : null;
        if (!(outcome instanceof ShoreBerth.Berth)) outcome = berthFor(boat, null);
        if (outcome instanceof ShoreBerth.Berth found) { berth = found; bank = found.bank(); water = found.water(); return true; }
        fallbackLanding("no_berth");
        return false;
    }

    /** The landing could not be completed: another berth (at most two), then stay afloat and mark the area. */
    private void fallbackLanding(String reason) {
        var boat = ridden();
        if (boat == null) { toAshore("landing_failed_no_hull", 0); return; }
        if (attempt < BERTH_ATTEMPTS) {
            attempt++;
            var failed = water;
            var outcome = berthFor(boat, p -> !p.equals(failed));
            if (outcome instanceof ShoreBerth.Berth found) {
                berth = found; bank = found.bank(); water = found.water();
                GameplayLog.event(entity, "aquatic", "landing retry reason={} attempt={} bank={} water={}", reason, attempt, bank, water);
                since = now();
                phase = VoyagePhase.RETURN;   // a fresh sail toward the new berth: same transaction, new limit
                boat.syncVoyage(phase.ordinal());
                blocked = replans = respots = refusals = routeFails = 0;
                queue.clear(); queued = false; kept.clear(); spots.clear(); used.clear(); current = null;
                return;
            }
        }
        markUnavailable(boat.blockPosition(), reason);
        lastRefusal = "landing_gave_up:" + reason;
        GameplayLog.event(entity, "aquatic", "shore trip gave up hat={} reason={} attempt={} unavailableFor={}", boat.getUUID(), reason, attempt, UNAVAILABLE_TICKS);
        backToAfloat(reason, true);
    }

    private void backToAfloat(String reason, boolean drift) {
        var boat = ridden();
        attempt = 0; berth = null; shoreReason = "none"; shoreUntil = 0; courtship = false;
        enter(VoyagePhase.AFLOAT, "shore_" + reason);
        if (boat != null && drift) boat.helm().drift(entity, Purpose.LANDING);
    }

    // ---- DOCK
    private String dock() {
        var boat = ridden();
        long now = now();
        if (boat == null) return Behaviors.IDLE;
        if (!boat.isAfloat() && boat.state() != HatBoatEntity.State.BEACHED || boat.unsteadyMotion() || boat.capsizing()) {
            if (++hullWait > 160) fallbackLanding("hull_unsteady");
            return Behaviors.BOAT_STEADY;
        }
        hullWait = 0;
        if (life.familyLife().alarm().preventsOuting()) { fallbackLanding("family_alarm"); return Behaviors.IDLE; }
        if (berth == null && !refreshBerth(boat)) return Behaviors.BOAT_CALL;
        if (now - since < 2) for (var rider : boat.getPassengers()) if (rider instanceof YukkuriEntity y) y.life().wakeInHat();
        var result = boat.helm().berth(entity, Purpose.LANDING, berth.center(), berth.yaw(), .07, 5f);
        double gap = HatDock.rimGap(boat, berth);
        if (result.arrived()) {
            GameplayLog.limited(entity, "aquatic", "dock.arrived", 100, "docked hat={} rimGap={} yaw={}", boat.getUUID(), gap, boat.getYRot());
            enter(VoyagePhase.DISEMBARK, "docked");
            return Behaviors.BOAT_LAND;
        }
        blocked = result.blocked() ? blocked + 1 : 0;
        if (blocked >= 15 || now - stageSince > 320) {
            boolean turned = Math.abs(Mth.wrapDegrees(berth.yaw() - boat.getYRot())) <= 12;
            if (gap <= .4 && turned) { enter(VoyagePhase.DISEMBARK, "close_enough"); return Behaviors.BOAT_LAND; }
            if (replans++ < 2 && berthFor(boat, null) instanceof ShoreBerth.Berth again) { berth = again; bank = again.bank(); water = again.water(); blocked = 0; stageSince = now; }
            else fallbackLanding("could_not_lie_alongside gap=" + String.format(Locale.ROOT, "%.2f", gap));
        }
        return Behaviors.BOAT_LAND;
    }

    // ---- DISEMBARK (the mirror of LOAD)
    private String disembark() {
        var boat = hull();
        long now = now();
        if (boat == null || boat.isRemoved()) { toAshore("disembark_hull_gone", 0); return Behaviors.IDLE; }
        boolean captainAboard = entity.getVehicle() == boat;
        if (!captainAboard && berth == null) {
            var outcome = bank != null && water != null ? ShoreBerth.berthAt(level(), entity, boat.hullScale(), bank, water, ShoreBerth.Purpose.LAND) : null;
            if (outcome instanceof ShoreBerth.Berth found) berth = found;
        }
        if (berth != null && boat.isAfloat()) boat.helm().berth(entity, Purpose.LANDING, berth.center(), berth.yaw(), .05, 4f);
        if (boat.unsteadyMotion() || boat.capsizing()) { if (++hullWait > 200) fail("hull_unsteady"); return Behaviors.BOAT_STEADY; }
        hullWait = 0;
        if (!queued) {
            queued = true;
            queue.clear(); kept.clear(); spots.clear(); used.clear(); current = null;
            if (captainAboard && !aborted) for (var passenger : boat.getPassengers())
                if (passenger instanceof YukkuriEntity child && child != entity) queue.add(child.getUUID());
            actorSince = now;
        }
        if (captainAboard) {
            if (current == null) {
                current = queue.poll();
                if (current == null) return captainOff(boat, now);
                actorSince = now; respots = refusals = 0; airSince = 0;
            }
            crewOff(boat, now);
            return Behaviors.BOAT_LAND;
        }
        // Captain already ashore: the young still aboard are set down in her braid, then the hat goes in.
        entity.getNavigation().stop();
        if (entity.isInWaterOrBubble()) { fail("captain_in_water"); return Behaviors.IDLE; }
        if (ashoreSince == 0) ashoreSince = now;
        if (CrewTransfers.releaseYoung(boat, entity)) {
            if (now - ashoreSince > 900) giveUpUnloading("young_not_unloadable");
            return Behaviors.BOAT_LAND;
        }
        enter(VoyagePhase.STOW, aborted ? "launch_stowed" : "landed");
        return Behaviors.BOAT_LAND;
    }

    /** One child's way off the hull; its failure changes its drop point or keeps it aboard, never ends the landing. */
    private void crewOff(HatBoatEntity boat, long now) {
        var actor = child(current);
        if (actor == null || !actor.isAlive()) { current = null; return; }
        if (actor.getVehicle() != boat) {
            if (actor.family().flying() || actor.family().crew().holdsBody() || entity.family().crew().busy()) { airSince = 0; return; }
            if (actor.isInWaterOrBubble()) {
                GameplayLog.event(entity, "aquatic", "disembark child fell into water child={}", current);
                current = null; return;
            }
            if (actor.onGround()) {
                used.add(actor.getBoundingBox()); actor.getNavigation().stop();
                boat.releaseTemporaryRefuge(actor, "shore_landing");
                GameplayLog.limited(entity, "aquatic", "disembark.ashore", 100, "disembark child ashore child={} at={}", current, actor.position());
                current = null; return;
            }
            if (airSince == 0) airSince = now;
            else if (now - airSince > 40) keep(actor, "left_hull_never_landed");
            return;
        }
        airSince = 0;
        if (now - actorSince > CHILD_UNLOAD_TICKS) { keep(actor, "took_too_long"); return; }
        if (actor.family().flying() || actor.family().crew().holdsBody() || entity.family().crew().busy()) return;
        var spot = spots.get(current);
        if (spot == null) {
            spot = dropPoint(actor, boat);
            if (spot == null) { keep(actor, "no_free_ground"); return; }
            spots.put(current, spot);
        }
        if (boat.changingSeat(actor)) return;
        boolean hops = CrewTransfer.canStepOut(actor);
        if (!hops && liftInfant(boat, actor, spot)) return;
        if (hops) {
            var hop = CrewTransfers.land(actor, null, boat, spot);
            if (hop.ok()) return;
            GameplayLog.limited(entity, "aquatic", "disembark.refused", 100, "disembark child={} spot={} reason={}:{}", current, spot, hop.refusal(), hop.reason());
            if (++refusals >= 24) respot(actor);
        } else if (++refusals >= 2) respot(actor);
    }

    private Vec3 dropPoint(YukkuriEntity actor, HatBoatEntity boat) {
        if (berth == null) return null;
        for (var spot : berth.walkOff()) {
            var box = actor.getBoundingBox().move(spot.subtract(actor.position())).inflate(.03);
            if (used.stream().noneMatch(box::intersects) && HatDock.standable(actor, spot)) return spot;
        }
        boolean hops = CrewTransfer.canStepOut(actor);
        if (hops) return HatDock.spotFor(berth, actor, actor.position(), used, respots);
        var taken = new ArrayList<AABB>(used);
        for (var other : level().getEntitiesOfClass(YukkuriEntity.class, boat.getBoundingBox().inflate(3)))
            if (other != actor && !other.isPassenger()) taken.add(other.getBoundingBox());
        return HatDock.spotFor(berth, actor, respots % 2 == 0 ? entity.position() : actor.position(), taken, respots / 2);
    }

    private void respot(YukkuriEntity actor) {
        spots.remove(actor.getUUID()); refusals = 0;
        if (++respots > MAX_RESPOTS) keep(actor, "no_way_found");
    }

    private void keep(YukkuriEntity actor, String why) {
        kept.add(actor.getUUID());
        GameplayLog.event(entity, "aquatic", "disembark child kept aboard child={} reason={} respots={}", actor.getUUID(), why, respots);
        current = null; respots = refusals = 0;
    }

    /** One lift over the brim if the captain's reach allows, else first to the brim place nearest the bank. */
    private boolean liftInfant(HatBoatEntity boat, YukkuriEntity infant, Vec3 spot) {
        var direct = CrewTransfers.land(infant, entity, boat, spot);
        if (direct.ok()) return true;
        if (boat.onBrim(infant)) return false;
        var near = new ArrayList<>(boat.brimSeatsToward(infant, spot, 96));
        near.sort(Comparator.comparingDouble(at -> { var w = boat.toWorld(at); return w.distanceTo(infant.position()) + w.distanceTo(spot); }));
        var tried = new ArrayList<Vec3>();
        for (var seat : near) {
            if (tried.size() >= 6) break;
            boolean far = false;
            for (var kept : tried) if (Math.hypot(kept.x - seat.x, kept.z - seat.z) < 2.5) { far = true; break; }
            if (far) continue;
            tried.add(seat);
            int slot = boat.deckLayout().slotAt(seat, .02);
            if (slot >= 0 && CrewTransfers.carryWithin(infant, entity, boat, slot, DeckLayout.NONE).ok()) return true;
        }
        return false;
    }

    private String captainOff(HatBoatEntity boat, long now) {
        // The children are off (or kept aboard); the captain follows last.
        if (entity.family().flying() || entity.family().crew().holdsBody()) return Behaviors.BOAT_LAND;
        if (captainSince == 0) { captainSince = now; respots = refusals = 0; captainSpot = null; }
        if (now - captainSince > CHILD_UNLOAD_TICKS) { fallbackLanding("captain_stuck"); return Behaviors.IDLE; }
        if (captainSpot == null) {
            if (berth != null) captainSpot = berth.walkOff().stream().filter(s -> HatDock.standable(entity, s)).findFirst().orElse(null);
            if (captainSpot == null && berth != null) captainSpot = HatDock.spotFor(berth, entity, entity.position(), used, respots);
            if (captainSpot == null) { fallbackLanding("no_ground_for_captain"); return Behaviors.IDLE; }
        }
        if (boat.changingSeat(entity)) return Behaviors.BOAT_LAND;
        var hop = CrewTransfers.land(entity, null, boat, captainSpot);
        if (!hop.ok() && ++refusals >= 24) { captainSpot = null; refusals = 0; if (++respots > MAX_RESPOTS) fallbackLanding("captain_no_way_found"); }
        return Behaviors.BOAT_LAND;
    }

    /** Children that cannot be put ashore stay aboard: the captain sails on with them and the area rests. */
    private void giveUpUnloading(String reason) {
        var boat = hull();
        GameplayLog.event(entity, "aquatic", "unloading gave up reason={} hat={} aboard={}", reason, hullId, boat == null ? 0 : boat.getPassengers().size());
        lastRefusal = "unloading_gave_up:" + reason;
        if (boat != null) markUnavailable(boat.blockPosition(), reason);
        recoverBlockedUntil = 0;
        manifest.clear();
        toAshore(reason, aborted ? pendingCooldown : 200);
        aborted = false;
    }

    // ---- STOW
    private String stow() {
        var boat = hull();
        long now = now();
        if (boat == null) { toAshore("stow_hull_gone", 0); return Behaviors.IDLE; }
        if (berth == null) {
            var outcome = bank != null && water != null ? ShoreBerth.berthAt(level(), entity, boat.hullScale(), bank, water, ShoreBerth.Purpose.LAND) : null;
            if (outcome instanceof ShoreBerth.Berth found) berth = found;
        }
        if (berth == null) { stowFailed("stow_no_berth"); return Behaviors.IDLE; }
        if (boat.isAfloat()) boat.helm().berth(entity, Purpose.LANDING, berth.center(), berth.yaw(), .05, 4f);
        if (entity.isInWaterOrBubble() || entity.isPassenger()) { stowFailed("stow_captain_not_on_land"); return Behaviors.IDLE; }
        if (entity.family().flying()) return Behaviors.BOAT_LAND;
        if (!hatStage) {
            double away = entity.position().subtract(berth.stand()).horizontalDistance();
            if (SurfaceApproach.walk(entity, berth.stand(), .8) || away <= .07) { hatStage = true; stageSince = now; return Behaviors.BOAT_LAND; }
            if (now - stageSince > 200) {
                if (away <= .35) { hatStage = true; stageSince = now; }
                else stowFailed("stow_cannot_reach_hat_place away=" + String.format(Locale.ROOT, "%.2f", away));
            }
            return Behaviors.BOAT_LAND;
        }
        SurfaceApproach.halt(entity);
        var placement = HatVessel.placement(true, HatVessel.Role.SELF);
        float facing = Mth.wrapDegrees(berth.yaw() - placement.yaw());
        SurfaceApproach.face(entity, facing);
        if (Math.abs(Mth.wrapDegrees(entity.yBodyRot - facing)) > 1.5f && now - stageSince < 80) return Behaviors.BOAT_LAND;
        if (!boat.finishLanding(entity, null)) { stowFailed("stow_hat_not_returned"); return Behaviors.IDLE; }
        entity.family().playHatOn();
        GameplayLog.event(entity, "aquatic", "landing complete hat={} duration={} hatReturned=true", boat.getUUID(), now - since);
        return Behaviors.AQUATIC_HAT_CARE;
    }

    /** The hat could not be put away: it stays on the water, the captain ashore, and the area is left alone for a while. */
    private void stowFailed(String reason) {
        var boat = hull();
        GameplayLog.event(entity, "aquatic", "stow failed reason={} hat={}", reason, hullId);
        lastRefusal = reason;
        if (boat != null) markUnavailable(boat.blockPosition(), reason);
        recoverBlockedUntil = now() + 200;
        toAshore(reason, aborted ? pendingCooldown : 200);
        aborted = false;
    }

    /** Called by the hull when the hat has been given back: the voyage is over. */
    public void landed() {
        aborted = false;
        int cool = Math.max(pendingCooldown, courtship ? 240 : 80);
        pendingCooldown = 0;
        life.afterLanding();
        hullId = null;
        toAshore("landed", cool);
        shoreReason = "none"; shoreUntil = 0; courtship = false;
        GameplayLog.event(entity, "aquatic", "voyage landed cooldown={}", cool);
    }

    // ---- RECOVER (captain ashore, own hull afloat or beached)
    private String recover() {
        var hat = life.selfRescue().recoverableHat();
        long now = now();
        if (hat == null) { toAshore("recover_hull_gone", 0); return Behaviors.IDLE; }
        hullId = hat.getUUID();
        if (hat.isAfloat() && !hat.unsteadyMotion() && !hat.hasIncoming() && hat.distanceToSqr(entity) <= 9) {
            entity.getNavigation().stop();
            hat.helm().hold(entity, Purpose.SAFETY);
            if ((now - since) % 10 == 1) CrewTransfers.embark(entity, hat);
            return Behaviors.BOAT_LAUNCH;
        }
        if (hat.state() == HatBoatEntity.State.BEACHED && hat.distanceToSqr(entity) <= SHORE_NEAR_SQUARED) {
            // The captain stands by the stranded hull and slides it back to the water along the nearest berth.
            entity.getNavigation().stop();
            if (berth == null || now >= nextRoute) {
                nextRoute = now + 40;
                var outcome = ShoreBerth.find(level(), new ShoreBerth.Query(entity, hat, hat.hullScale(), hat.blockPosition(), ShoreBerth.Purpose.LAND, null,
                        ShoreBerth.defaultRange(ShoreBerth.Purpose.LAND), 0, p -> true));
                if (outcome instanceof ShoreBerth.Berth found) berth = found;
                else {
                    lastRefusal = "refloat_no_berth:" + ((ShoreBerth.Refusal) outcome).describe();
                    GameplayLog.limited(entity, "aquatic", "recover.noberth", 400, "recover refloat found no berth {}", ((ShoreBerth.Refusal) outcome).describe());
                    berth = null;
                }
            }
            if (berth != null) hat.helm().berth(entity, Purpose.LANDING, berth.center(), berth.yaw(), .05, 4f);
            return Behaviors.BOAT_PUSH_OFF;
        }
        return life.selfRescue().shoreRecoveryTick() ? life.selfRescue().shoreRecoveryActivity() : Behaviors.IDLE;
    }

    // ================================================================= persistence
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString("Phase", phase.name());
        tag.putLong("Since", since);
        tag.putInt("Attempt", attempt);
        tag.putInt("Cooldown", cooldown);
        if (bank != null) tag.putLong("Bank", bank.asLong());
        if (water != null) tag.putLong("Water", water.asLong());
        if (hullId != null) tag.putUUID("Hull", hullId);
        tag.putString("LastRefusal", lastRefusal);
        tag.putBoolean("Birth", birth);
        tag.putLong("RecoverBlocked", recoverBlockedUntil);
        return tag;
    }

    public void load(CompoundTag tag) {
        try { phase = VoyagePhase.valueOf(tag.getString("Phase")); } catch (IllegalArgumentException none) { phase = VoyagePhase.ASHORE; }
        since = tag.getLong("Since");
        attempt = Math.clamp(tag.getInt("Attempt"), 0, 8);
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 1200);
        bank = tag.contains("Bank") ? BlockPos.of(tag.getLong("Bank")) : null;
        water = tag.contains("Water") ? BlockPos.of(tag.getLong("Water")) : null;
        hullId = tag.hasUUID("Hull") ? tag.getUUID("Hull") : null;
        lastRefusal = tag.getString("LastRefusal");
        birth = tag.getBoolean("Birth");
        recoverBlockedUntil = tag.getLong("RecoverBlocked");
        // Transient pieces (manifest, queue, berth) are rebuilt; a launch interrupted by a reload starts over from the bank.
        berth = null; manifest.clear(); queue.clear(); queued = false; kept.clear(); spots.clear(); used.clear(); current = null;
        if (phase == VoyagePhase.PREPARE || phase == VoyagePhase.MUSTER || phase == VoyagePhase.MOOR || phase == VoyagePhase.LOAD || phase == VoyagePhase.EMBARK) {
            phase = VoyagePhase.ASHORE; since = 0;
        }
    }
}
