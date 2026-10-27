package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriLocomotion;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * One incident per person in the water, and the only place that decides whether anyone helps.
 *
 * <p>An incident is opened where a body really enters the water ({@code WaterDistress.plunge}: a capsize, a shake, a squeeze-out,
 * a missed birth catch) and lives on the victim's {@link CrewPort} until the victim is aboard, ashore or dead. Every
 * (rescuer, incident) pair is decided exactly once, with the friendliness formula
 * ({@code YukkuriPersonality.helpProbability}); the decision is stored on the incident and every later look at the same pair, also
 * after the captain has climbed back aboard her hull, reads it back. Physical failures of a rescue (a refused or broken lift) are
 * counted on the incident per rescuer, and the self-rescue counts its own slips and refused entries on it as well.
 *
 * <p>Who can be a rescuer is fixed by what a yukkuri's body can do: a parent aboard her hull, the capsized captain herself (in the
 * water, but able to lift from there), a neighbour aboard her own hull, or a land yukkuri on the bank within braid or mouth reach.
 * None of them jumps in to swim. The lifts themselves are {@link CrewTransfers}; the drivers of the hull-borne rescuers (the
 * parent's and the neighbour's approach) stay with the family life that steers the hull and only ask {@link #permits}.
 */
public final class WaterIncidents {
    public enum Cause {
        CAPSIZE, SHAKEN, SQUEEZED, BIRTH_MISS, HULL_LOST, OWNER_DIED, FALL, OTHER;
        /** The body was thrown out of the boat (it shows the thrown pose in the air). */
        public boolean thrown() { return this == CAPSIZE || this == SHAKEN; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
        /** The cause a transfer's reason string names. */
        public static Cause ofWhy(String why) {
            if (why == null) return OTHER;
            if (why.startsWith("squeeze")) return SQUEEZED;
            if (why.startsWith("birth")) return BIRTH_MISS;
            return FALL;
        }
    }
    public enum Role { SAME_BOAT_PARENT, CAPTAIN_SELF, NEIGHBOUR, BANK }
    public record Decision(Role role, boolean accepted, float chance, float roll, boolean familiar, boolean urgent, long at) {}

    /** Physical failures a rescuer may have on one incident (the first try, one re-approach, one more) before the call goes out unanswered. */
    public static final int MAX_FAILURES = 3;
    /** Slips of the swimmer's own climbs on one incident; the approach limit is for the swim to the hull. */
    public static final int SELF_SLIPS = 3, APPROACH_LIMIT = 600;
    private static final int SCAN_EVERY = 10, MAX_DECISIONS = 10, BANK_ATTEMPT_EVERY = 20, REACH_LIMIT = 300, REACH_STALL = 60;

    /** A physical approach belongs to the same incident as its decision and failures, never to a caller's retry loop. */
    private static final class Approach {
        long started, progressed, observed = Long.MIN_VALUE, retryAt, failedAt = Long.MIN_VALUE;
        double bestGap = Double.POSITIVE_INFINITY;
        String entry = "";
        final Set<String> refused = new LinkedHashSet<>();
        Approach(long now) { started = progressed = now; }
    }

    public static final class Incident {
        private final UUID victim;
        private UUID boat, pusher;
        private Cause cause;
        private final long opened;
        private final Map<UUID, Decision> decisions = new LinkedHashMap<>();
        private final Map<UUID, Integer> failures = new HashMap<>();
        private final Map<UUID, Approach> reaching = new HashMap<>();
        private final Set<String> refusedEntries = new LinkedHashSet<>();
        private int slips, approachTicks, attempts;
        private boolean shoreDrift, closed;
        private long nextScan, nextAttempt;
        private String lastFailure = "";

        private Incident(UUID victim, UUID boat, UUID pusher, Cause cause, long opened) {
            this.victim = victim; this.boat = boat; this.pusher = pusher; this.cause = cause; this.opened = opened;
        }
        public UUID victim() { return victim; }
        public UUID boat() { return boat; }
        public Cause cause() { return cause; }
        public long opened() { return opened; }
        public boolean closed() { return closed; }
        public int slips() { return slips; }
        public int attempts() { return attempts; }
        public int approachTicks() { return approachTicks; }
        /** Self-rescue has given up the hull for this incident: the swimmer drifts weakly toward the nearest shore. */
        public boolean shoreDrift() { return shoreDrift; }
        public int failuresOf(UUID rescuer) { return failures.getOrDefault(rescuer, 0); }
        public Map<UUID, Decision> decisions() { return Map.copyOf(decisions); }
        public boolean refused(String entryId) { return entryId != null && refusedEntries.contains(entryId); }
        public int refusedEntries() { return refusedEntries.size(); }
        public String lastFailure() { return lastFailure; }
    }

    // ================================================================= opening and closing
    /** Opens the incident of a body entering the water, or returns the one it is already in. */
    public static Incident open(YukkuriEntity victim, UUID boat, UUID pusher, Cause cause) {
        if (victim.level().isClientSide) return null;
        var port = victim.family().crew();
        var existing = port.incident();
        if (existing != null && !existing.closed) {
            // The same fall seen again is the same incident; a real cause replaces a guess.
            if (existing.cause == Cause.OTHER && cause != Cause.OTHER) existing.cause = cause;
            if (existing.boat == null) existing.boat = boat;
            if (existing.pusher == null) existing.pusher = pusher;
            return existing;
        }
        var incident = new Incident(victim.getUUID(), boat, pusher, cause, victim.level().getGameTime());
        port.incident(incident);
        GameplayLog.event(victim, "aquatic", "incident open victim={} cause={} boat={} pusher={} stage={} inWater={}",
                victim.getUUID(), cause.id(), boat, pusher, victim.family().stage(), victim.isInWaterOrBubble());
        return incident;
    }

    public static Incident of(YukkuriEntity victim) {
        var incident = victim.family().crew().incident();
        return incident == null || incident.closed ? null : incident;
    }

    private static void close(YukkuriEntity victim, Incident incident, String outcome) {
        if (incident.closed) return;
        incident.closed = true;
        GameplayLog.event(victim, "aquatic", "incident close victim={} outcome={} cause={} ticks={} decisions={} attempts={} slips={} refusedEntries={} shoreDrift={}",
                victim.getUUID(), outcome, incident.cause.id(), victim.level().getGameTime() - incident.opened, summary(incident),
                incident.attempts, incident.slips, incident.refusedEntries.size(), incident.shoreDrift);
        victim.family().crew().incident(null);
        victim.family().crew().settled();
    }

    // ================================================================= the clock
    /** Called once per game tick from the victim's own {@code WaterDistress} clock. */
    public static void tick(YukkuriEntity victim) {
        if (victim.level().isClientSide || !(victim.level() instanceof ServerLevel level)) return;
        var port = victim.family().crew();
        var incident = port.incident();
        if (!victim.isAlive()) {
            if (incident != null) close(victim, incident, "death");
            return;
        }
        boolean water = victim.isInWaterOrBubble();
        long now = level.getGameTime();
        if (incident == null || incident.closed) {
            if (!water || victim.isPassenger() || port.active()) return;
            // A swimmer nobody threw: it is opened by what is seen.
            incident = open(victim, null, null, Cause.OTHER);
        }
        // A ticket owns the body while it runs; a failed one lets the body fall again into this very incident.
        if (port.active()) return;
        if (victim.getVehicle() instanceof HatBoatEntity) { close(victim, incident, "aboard"); return; }
        if (!water) {
            var distress = victim.waterDistress();
            if (distress.airbornePlunge() || victim.family().flying()) return;
            if (distress.phase() == WaterDistress.Phase.RECOVER) { close(victim, incident, "ashore"); return; }
            if (now - incident.opened > 60 && distress.phase() == WaterDistress.Phase.IDLE) close(victim, incident, "dry");
            return;
        }
        if (now < incident.nextScan || victim.waterDistress().phase() == WaterDistress.Phase.IMPACT) return;
        incident.nextScan = now + SCAN_EVERY;
        scan(level, victim, incident, now);
    }

    private static void scan(ServerLevel level, YukkuriEntity victim, Incident incident, long now) {
        var near = new ArrayList<YukkuriEntity>();
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, victim.getBoundingBox().inflate(16, 6, 16), y -> y != victim && y.isAlive() && !y.isNoAi()))
            near.add(y);
        near.sort(Comparator.<YukkuriEntity>comparingInt(y -> Bonds.parentOf(y, victim) ? 0 : 1)
                .thenComparingDouble(victim::distanceToSqr));
        int considered = 0;
        for (var y : near) {
            if (considered >= 12) break;
            var role = roleOf(y, victim);
            if (role == null) continue;
            considered++;
            if (!incident.decisions.containsKey(y.getUUID())) decide(y, victim, incident, role);
        }
        // The land yukkuri pulls from the bank: only within reach, never into the water.
        if (now >= incident.nextAttempt) {
            for (var y : near) {
                var d = incident.decisions.get(y.getUUID());
                if (d == null || d.role() != Role.BANK || !d.accepted() || incident.failuresOf(y.getUUID()) >= MAX_FAILURES) continue;
                if (roleOf(y, victim) != Role.BANK || !permits(y, victim)) continue;
                if (y.family().crew().active() || victim.family().crew().active() || !victim.waterDistress().canBeLifted()) continue;
                incident.nextAttempt = now + BANK_ATTEMPT_EVERY;
                var result = CrewTransfers.pullToBank(victim, y);
                attempted(y, victim, "pull_to_bank", result);
                break;
            }
        }
    }

    // ================================================================= who may help, and the one decision
    /** The part a yukkuri could play in this swimmer's incident from where she is now, or null. */
    private static Role roleOf(YukkuriEntity y, YukkuriEntity victim) {
        if (y == victim || !y.isAlive() || y.isNoAi() || y.isCommandPlaying() || y.operations().active()
                || y.level() != victim.level() || !y.life().canRecognize(victim)) return null;
        if (y.getVehicle() instanceof HatBoatEntity hat && hat.isAfloat() && y.getUUID().equals(hat.ownerYukkuri()) && y.profile().aquatic()) {
            if (victim.family().adult() && !Bonds.parentOf(y, victim)) return null;
            return Bonds.parentOf(y, victim) ? Role.SAME_BOAT_PARENT : Role.NEIGHBOUR;
        }
        if (y.isInWaterOrBubble()) {
            var incident = of(victim);
            var source = incident != null && incident.boat != null && y.level() instanceof ServerLevel server
                    && server.getEntity(incident.boat) instanceof HatBoatEntity hat ? hat : null;
            // Kinship alone never makes a sibling or a baby the captain of the boat that threw this swimmer.
            return y.profile().aquatic() && source != null && source.ownsPhysicalHat(y) && !victim.family().adult()
                    ? Role.CAPTAIN_SELF : null;
        }
        if (y.isPassenger() || !y.onGround() || y.profile().dangerToYukkuri() || y.isPanicking()) return null;
        double dx = victim.getX() - y.getX(), dz = victim.getZ() - y.getZ();
        // Only a body that could lift this swimmer at all; the braid or mouth reach itself is checked when the lift is planned.
        return Math.hypot(dx, dz) <= 2.2 && Math.abs(victim.getY() - y.getY()) <= 1.6 && CrewReach.canLift(y, victim) ? Role.BANK : null;
    }

    /** Draws the one decision of (rescuer, incident) and records it; the answer to every later ask. */
    public static Decision decide(YukkuriEntity rescuer, YukkuriEntity victim, Incident incident, Role role) {
        var known = incident.decisions.get(rescuer.getUUID());
        if (known != null) return known;
        // Keep every draw for the whole incident. The two recorded parents retain room even after nearby bystanders arrive.
        if (incident.decisions.size() >= MAX_DECISIONS && !Bonds.parentOf(rescuer, victim)) {
            GameplayLog.limited(victim, "aquatic", "incident.decisions_full", 200,
                    "incident decision deferred rescuer={} reason=decision_capacity", rescuer.getUUID());
            return null;
        }
        long now = rescuer.level().getGameTime();
        boolean familiar = Bonds.careKin(rescuer, victim)
                || YukkuriWorldData.get((ServerLevel) rescuer.level()).affinity(rescuer.getUUID(), victim.getUUID()) >= BondLevels.AFFINITY_FAMILIAR;
        boolean urgent = !victim.childhood().canJump() || victim.waterDistress().phase() == WaterDistress.Phase.EXHAUSTED
                || victim.getHealth() <= victim.getMaxHealth() * .5f;
        float chance = rescuer.life().personality().helpProbability(familiar, urgent);
        float roll = rescuer.getRandom().nextFloat();
        var decision = new Decision(role, roll < chance, chance, roll, familiar, urgent, now);
        incident.decisions.put(rescuer.getUUID(), decision);
        GameplayLog.event(rescuer, "aquatic", "incident decide victim={} rescuer={} role={} familiar={} urgent={} chance={} roll={} accepted={}",
                victim.getUUID(), rescuer.getUUID(), role, familiar, urgent, chance, roll, decision.accepted());
        if (decision.accepted()) rescuer.showReaction("teary", "braid_rescue_offer", 40);
        else if (role == Role.NEIGHBOUR || role == Role.BANK) {
            // A bystander who will not help leans out and cheers: the call is heard, nothing else happens.
            rescuer.getLookControl().setLookAt(victim, 30, 30);
            rescuer.showReaction("surprise", null, 40);
        }
        return decision;
    }

    /**
     * Whether {@code rescuer} will bring this swimmer aboard: reads (or draws, the first time) the one decision of this pair, and is
     * false once her physical tries on this incident are spent. The lift code asks; it never decides.
     */
    public static boolean permits(YukkuriEntity rescuer, YukkuriEntity victim) {
        if (rescuer.level().isClientSide) return false;
        var incident = of(victim);
        if (incident == null) {
            if (!victim.isInWaterOrBubble()) return true;
            incident = open(victim, null, null, Cause.OTHER);
        }
        var role = roleOf(rescuer, victim);
        if (role == null) return false;
        var known = incident.decisions.get(rescuer.getUUID());
        if (known == null) {
            known = decide(rescuer, victim, incident, role);
        }
        var approach = incident.reaching.get(rescuer.getUUID());
        return known != null && known.accepted() && incident.failuresOf(rescuer.getUUID()) < MAX_FAILURES
                && (approach == null || rescuer.level().getGameTime() >= approach.retryAt);
    }

    /** The swimmer's decision for this rescuer, or null while none has been drawn. */
    public static Decision decisionFor(YukkuriEntity rescuer, YukkuriEntity victim) {
        var incident = of(victim);
        return incident == null ? null : incident.decisions.get(rescuer.getUUID());
    }

    // ================================================================= physical attempts
    /** One try of a lift was opened (or refused at once): the log line, and the failure counted when it was a real one. */
    public static void attempted(YukkuriEntity rescuer, YukkuriEntity victim, String how, CrewTransfer.Result result) {
        var incident = of(victim);
        if (incident == null) return;
        if (result.ok()) {
            incident.reaching.remove(rescuer.getUUID());
            incident.attempts++;
            GameplayLog.event(rescuer, "aquatic", "incident attempt victim={} rescuer={} how={} n={} opened=true", victim.getUUID(), rescuer.getUUID(), how, incident.attempts);
            return;
        }
        // A refusal that only says "not yet" (busy, hull unsteady) waits; the others are physical failures of this rescuer.
        if (result.refusal() == CrewTransfer.Refusal.TRANSIENT) {
            GameplayLog.limited(rescuer, "aquatic", "incident.wait." + victim.getId(), 100, "incident attempt waits victim={} rescuer={} how={} reason={}",
                    victim.getUUID(), rescuer.getUUID(), how, result.reason());
            return;
        }
        failed(rescuer, victim, incident, how, result.refusal().name() + ":" + result.reason());
    }

    /** A ticket of a rescue ended without bringing the body to its place. */
    public static void transferFailed(YukkuriEntity carrier, YukkuriEntity victim, CrewTransfer.Refusal refusal, String reason) {
        var incident = of(victim);
        if (incident == null || carrier == null) return;
        failed(carrier, victim, incident, "transfer", (refusal == null ? "-" : refusal.name()) + ":" + reason);
    }

    private static void failed(YukkuriEntity rescuer, YukkuriEntity victim, Incident incident, String how, String why) {
        long now = rescuer.level().getGameTime();
        var approach = incident.reaching.computeIfAbsent(rescuer.getUUID(), id -> new Approach(now));
        // Opening a ticket can fail synchronously and also notify its caller. That is one physical failure.
        if (approach.failedAt == now) return;
        approach.failedAt = now;
        approach.retryAt = now + BANK_ATTEMPT_EVERY;
        approach.started = approach.progressed = now;
        approach.bestGap = Double.POSITIVE_INFINITY;
        if (!approach.entry.isEmpty()) approach.refused.add(approach.entry);
        approach.entry = "";
        int n = incident.failures.merge(rescuer.getUUID(), 1, Integer::sum);
        incident.lastFailure = why;
        GameplayLog.event(rescuer, "aquatic", "incident attempt failed victim={} rescuer={} how={} reason={} failures={}/{}{}",
                victim.getUUID(), rescuer.getUUID(), how, why, n, MAX_FAILURES, n >= MAX_FAILURES ? " spent=true" : "");
        // The call goes out and nobody answers any more from this rescuer: the swimmer's own efforts and the water decide.
        if (n >= MAX_FAILURES) victim.waterDistress().callForHelp();
    }

    // ================================================================= the capsize: the captain's one decision
    /**
     * The hull has thrown its crew. The capsized captain decides, once, for each of her kin in the water: a friendly one pushes the
     * baby nearest her toward the brim (a real shove) before she sees to the hat; an unfriendly one grabs for her hat first. The same
     * decision is read back when she is aboard again.
     */
    public static void onCapsize(HatBoatEntity boat, YukkuriEntity captain, List<YukkuriEntity> thrown) {
        // Only a captain who is in the water herself decides here; one ashore is a bystander like any other and is drawn by the scan.
        if (captain == null || thrown.isEmpty() || !thrown.contains(captain)) return;
        boolean anyAccepted = false;
        YukkuriEntity nearestBaby = null;
        for (var victim : thrown) {
            if (victim == captain) continue;
            var incident = of(victim);
            if (incident == null) continue;
            var decision = decide(captain, victim, incident, Role.CAPTAIN_SELF);
            if (decision == null || !decision.accepted()) continue;
            anyAccepted = true;
            if (!victim.childhood().canJump() && (nearestBaby == null || captain.distanceToSqr(victim) < captain.distanceToSqr(nearestBaby))) nearestBaby = victim;
        }
        if (!anyAccepted) {
            captain.showReaction("surprise", "hat_drifting", 60);
            GameplayLog.event(captain, "aquatic", "incident captain hat_first hull={} kin={}", boat.getUUID(), thrown.size() - 1);
            return;
        }
        if (nearestBaby != null) {
            var toward = boat.position().subtract(nearestBaby.position()).multiply(1, 0, 1);
            if (toward.lengthSqr() > 1e-6) {
                var push = toward.normalize().scale(.05);
                YukkuriLocomotion.waterImpulse(nearestBaby, new Vec3(push.x, .02, push.z));
            }
            GameplayLog.event(captain, "aquatic", "incident captain push_to_brim baby={} hull={}", nearestBaby.getUUID(), boat.getUUID());
        }
    }

    // ================================================================= the capsized captain lifts her own
    /**
     * The captain, afloat beside the hull she has righted, brings the kin whose incidents she accepted onto it, one at a time.
     * True while she is busy with it (a lift under way, or swimming to the next swimmer); false when nothing is left to do.
     */
    public static boolean captainTick(YukkuriEntity captain, HatBoatEntity hat) {
        if (hat == null || !hat.isAfloat() || hat.unsteadyMotion() || captain.isPassenger() || !(captain.level() instanceof ServerLevel level)) return false;
        if (captain.family().crew().active()) return true;
        YukkuriEntity pick = null;
        Incident pickIncident = null;
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, captain.getBoundingBox().inflate(10, 4, 10),
                y -> y != captain && y.isAlive() && !y.isPassenger() && y.isInWaterOrBubble())) {
            var incident = of(y);
            if (incident == null) continue;
            var d = incident.decisions.get(captain.getUUID());
            if (d == null || !d.accepted() || incident.failuresOf(captain.getUUID()) >= MAX_FAILURES) continue;
            if (!permits(captain, y) || !y.waterDistress().availableForWaterRescue(captain)) continue;
            if (!CrewReach.canLift(captain, y)) continue;
            if (pick == null || y.getHealth() < pick.getHealth()) { pick = y; pickIncident = incident; }
        }
        if (pick == null) return false;
        if (!guide(captain, pick, hat)) return false;
        captain.getNavigation().stop();
        SurfaceApproach.face(captain, SurfaceApproach.yaw(captain.position(), pick.position()));
        var result = CrewTransfers.liftFromWater(pick, captain, hat);
        if (result.ok()) {
            pick.waterDistress().lifted();
            attempted(captain, pick, "captain_lift", result);
            return true;
        }
        if (result.refusal() == CrewTransfer.Refusal.INCAPABLE) { failed(captain, pick, pickIncident, "captain_lift", "INCAPABLE:" + result.reason()); return false; }
        if (!approaching(captain, pick, captain.distanceTo(pick), result.reason())) return false;
        // Not in reach yet: a small swimming impulse toward the swimmer; the lift refuses until the braid reaches.
        if (!swimToward(captain, pick.position())) {
            failed(captain, pick, pickIncident, "captain_reach", "energy");
            return false;
        }
        return true;
    }

    /** Water movement is a small impulse and consumes effort; it never teleports the swimmer to the child. */
    private static boolean swimToward(YukkuriEntity actor, Vec3 target) {
        if (!actor.life().spendEnergy(.018f)) return false;
        return YukkuriLocomotion.swim(actor, target, .09);
    }

    /**
     * Between two births a mother afloat in her hull may lift a newborn that fell short of the rim: the nearest of her own young in the
     * water within {@code radius} that has an open incident. The decision of that incident gates the lift (read in {@code liftAboard}).
     */
    public static boolean liftNearby(YukkuriEntity captain, HatBoatEntity hat, double radius) {
        if (!hat.isAfloat() || hat.unsteadyMotion() || captain.isPanicking() || !(hat.level() instanceof ServerLevel level)) return false;
        var swimmers = level.getEntitiesOfClass(YukkuriEntity.class, hat.getBoundingBox().inflate(radius, 2, radius),
                y -> y.isAlive() && !y.isPassenger() && y.isInWaterOrBubble() && hat.young(y) && of(y) != null);
        swimmers.sort(Comparator.comparingDouble(hat::distanceToSqr));
        for (var y : swimmers) if (CrewTransfers.liftAboard(y, hat).ok()) return true;
        return false;
    }

    /** The captain is working on this swimmer (accepted, tries left): the swimmer waits at the hull rather than leaving. */
    public static boolean guiding(YukkuriEntity captain, YukkuriEntity victim) {
        var incident = of(victim);
        if (incident == null) return false;
        var d = incident.decisions.get(captain.getUUID());
        var approach = incident.reaching.get(captain.getUUID());
        return d != null && d.accepted() && roleOf(captain, victim) != null && incident.failuresOf(captain.getUUID()) < MAX_FAILURES
                && (approach == null || captain.level().getGameTime() >= approach.retryAt);
    }

    /** A willing, currently eligible rescuer may renew one actual rendezvous; another live guide keeps ownership. */
    public static boolean guide(YukkuriEntity rescuer, YukkuriEntity victim, HatBoatEntity hat) {
        if (!permits(rescuer, victim)) return false;
        return hat.young(victim) ? victim.waterDistress().guideToWaterRescue(rescuer, hat)
                : victim.waterDistress().guideToTemporaryRefuge(rescuer, hat);
    }

    /** Keep one entry while approaching. Failed entries of this physical rescue are not immediately retried. */
    public static String approachEntry(YukkuriEntity rescuer, YukkuriEntity victim, List<String> entries) {
        var incident = of(victim);
        if (incident == null || !guiding(rescuer, victim)) return null;
        var approach = incident.reaching.computeIfAbsent(rescuer.getUUID(), id -> new Approach(rescuer.level().getGameTime()));
        if (entries.contains(approach.entry) && !approach.refused.contains(approach.entry)) return approach.entry;
        approach.entry = entries.stream().filter(id -> !approach.refused.contains(id)).findFirst().orElse("");
        approach.bestGap = Double.POSITIVE_INFINITY;
        if (!approach.entry.isEmpty()) GameplayLog.event(rescuer, "aquatic",
                "incident approach victim={} rescuer={} entry={} failures={}", victim.getUUID(), rescuer.getUUID(), approach.entry, incident.failuresOf(rescuer.getUUID()));
        return approach.entry.isEmpty() ? null : approach.entry;
    }

    /** The swimmer reads the entry its rescuer is already approaching; it never selects a competing entrance. */
    public static String rescueEntry(YukkuriEntity rescuer, YukkuriEntity victim) {
        var incident = of(victim);
        var approach = incident == null ? null : incident.reaching.get(rescuer.getUUID());
        return approach == null || approach.entry.isEmpty() ? null : approach.entry;
    }

    /** Observe real progress toward contact, including refused helm moves; repeated calls cannot renew the deadline. */
    public static boolean approaching(YukkuriEntity rescuer, YukkuriEntity victim, double gap, String reason) {
        var incident = of(victim);
        if (incident == null || !guiding(rescuer, victim)) return false;
        long now = rescuer.level().getGameTime();
        var approach = incident.reaching.computeIfAbsent(rescuer.getUUID(), id -> new Approach(now));
        if (approach.observed == now) return true;
        approach.observed = now;
        if (Double.isFinite(gap) && gap < approach.bestGap - .04) { approach.bestGap = gap; approach.progressed = now; }
        if (now - approach.started < REACH_LIMIT && now - approach.progressed < REACH_STALL) return true;
        failed(rescuer, victim, incident, "approach", "no_contact:" + reason);
        return false;
    }

    // ================================================================= the swimmer's own efforts
    /** One slip of a climb; true once the budget of this incident is spent and the swimmer turns toward the shore. */
    public static boolean selfSlip(YukkuriEntity victim, String reason) {
        var incident = of(victim);
        if (incident == null) return false;
        incident.slips++;
        if (incident.slips >= SELF_SLIPS && !incident.shoreDrift) {
            giveUpHull(victim, incident, "slips:" + reason);
        }
        return incident.shoreDrift;
    }

    /**
     * A deterministic refusal at this entry: it is not tried again, the swimmer goes to another one. All entries refused turns her
     * toward the shore.
     */
    public static void selfRefused(YukkuriEntity victim, HatBoatEntity hat, String entryId, String reason) {
        var incident = of(victim);
        if (incident == null || entryId == null) return;
        incident.refusedEntries.add(entryId);
        GameplayLog.event(victim, "aquatic", "incident entry refused victim={} entry={} reason={} refused={}/{}", victim.getUUID(), entryId, reason,
                incident.refusedEntries.size(), hat == null ? 0 : hat.deckLayout().entries().size());
        if (hat != null && incident.refusedEntries.size() >= hat.deckLayout().entries().size() && !incident.shoreDrift) giveUpHull(victim, incident, "all_entries_refused");
    }

    /** Counts a tick spent swimming toward the hull; past the limit the swimmer turns toward the shore. */
    public static void approachTick(YukkuriEntity victim) {
        var incident = of(victim);
        if (incident == null || incident.shoreDrift) return;
        if (++incident.approachTicks > APPROACH_LIMIT) giveUpHull(victim, incident, "approach_timeout");
    }

    private static void giveUpHull(YukkuriEntity victim, Incident incident, String why) {
        incident.shoreDrift = true;
        GameplayLog.event(victim, "aquatic", "incident self_giveup victim={} reason={} slips={} approachTicks={} refused={}", victim.getUUID(), why,
                incident.slips, incident.approachTicks, incident.refusedEntries.size());
    }

    // ================================================================= readouts
    private static String summary(Incident incident) {
        var out = new StringBuilder("[");
        for (var e : incident.decisions.entrySet()) {
            var d = e.getValue();
            if (out.length() > 1) out.append(',');
            out.append(d.role()).append(d.accepted() ? "+" : "-").append(String.format(Locale.ROOT, "(%.2f/%.2f)", d.chance(), d.roll()));
        }
        return out.append(']').toString();
    }

    /** The incident of this swimmer, for its distress readout. */
    public static String describe(YukkuriEntity victim) {
        var incident = of(victim);
        if (incident == null) return "incident=none";
        return "incident cause=" + incident.cause.id() + " age=" + (victim.level().getGameTime() - incident.opened) + " decisions=" + summary(incident)
                + " attempts=" + incident.attempts + " failures=" + incident.failures.values() + " slips=" + incident.slips + "/" + SELF_SLIPS
                + " approach=" + incident.approachTicks + "/" + APPROACH_LIMIT + " refused=" + incident.refusedEntries + " shoreDrift=" + incident.shoreDrift
                + " last=" + (incident.lastFailure.isEmpty() ? "none" : incident.lastFailure);
    }

    /** Every open incident within reach of this reader, one line each. */
    public static String listing(YukkuriEntity reader) {
        if (!(reader.level() instanceof ServerLevel level)) return "";
        var lines = new ArrayList<String>();
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, reader.getBoundingBox().inflate(32, 16, 32), y -> y.isAlive())) {
            var incident = of(y);
            if (incident != null) lines.add("#" + y.getId() + " " + describe(y));
        }
        return lines.isEmpty() ? "incidents=0" : "incidents=" + lines.size() + " " + String.join(" | ", lines);
    }

    private WaterIncidents() {}
}
