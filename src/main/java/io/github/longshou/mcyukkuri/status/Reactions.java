package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.mind.ExcitedLeak;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.TravelRange;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.DenSeal;
import io.github.longshou.mcyukkuri.settlement.FoundShelter;
import io.github.longshou.mcyukkuri.motor.SideContact;
import io.github.longshou.mcyukkuri.settlement.SearchBudget;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Perception and reaction arbitration for ordinary (prey) individuals.
 *
 * <p>Why this exists: before, a yukkuri only reacted to a predator once that predator had already started a hunt
 * (PredatorLife.alertPrey), and weather only touched the wetness number. Nothing looked at the world. Here every
 * eligible individual perceives (a visible predator, a warning from kin, an attacker, rain/storm/snow on its own
 * block) and a single arbiter decides what to do, by the order the goals are registered in
 * {@code YukkuriEntity.registerGoals}:
 * <pre>
 *   0 water / operation / house exit            (survival, owner contact)
 *   1 panic flee, shell, THREAT (this class)    (emergency)
 *   2 WEATHER (this class)                      (needs: get out of the rain)
 *   3 life (food, sleep, toilet, temperature) ... 4-6 family, settlement, social ... 7 stroll
 * </pre>
 * Threat outranks weather; both outrank every social and idle goal; real emergencies of the body (starving, a full
 * bowel) outrank the weather goal because it yields to them.
 */
public final class Reactions {
    private final YukkuriEntity entity;
    private final Threat threat;
    private final Weather weather;

    Reactions(YukkuriEntity entity) { this.entity = entity; threat = new Threat(entity); weather = new Weather(entity); }

    public Threat threat() { return threat; }
    public Weather weather() { return weather; }
    /** Actual running counters for stationary responses, not a new waiting clock. */
    public Integer observedWaitElapsed() {
        if (threat.stage == Threat.Stage.HIDE) return threat.ticks;
        if (weather.stage == Weather.Stage.SHAKE || weather.stage == Weather.Stage.HUDDLE
                && (weather.partner == null || weather.pressedAt >= 0 || !weather.leads)) return weather.ticks;
        return null;
    }
    public boolean active() { return threat.stage != Threat.Stage.NONE || weather.stage != Weather.Stage.NONE; }
    /** Behavior id for the shared presentation chain (see EcologyPresentation). */
    public String activity() { return threat.stage != Threat.Stage.NONE ? threat.activity() : weather.activity(); }
    public String describe() {
        return threat.stage != Threat.Stage.NONE ? threat.describe() : weather.stage != Weather.Stage.NONE ? weather.describe() : "none";
    }
    void scan() { threat.scan(); weather.scan(); }
    void stop() { threat.seal.reset(); threat.stage = Threat.Stage.NONE; weather.stage = Weather.Stage.NONE; }

    /** Kin hear the alarm; nearby witnesses also react when they see a person inflict harm. */
    void hurtBy(DamageSource source) {
        threat.seal.abort("attacked");
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker == entity) return;
        threat.attackedBy(attacker);
        // The binding transaction is synchronous. Its close witnesses react to the hook that actually starts.
        if(!entity.operations().strikingHook())threat.warnKin(attacker);
        if (attacker instanceof Player player) HumanNpc.witnessedStreetHurt(entity, player);
    }

    // ---- shared predicates -------------------------------------------------------------------------------------

    /** An ordinary free-living or kept individual: not a predator, not a house yukkuri. */
    static boolean prey(YukkuriEntity y) {
        return y.isAlive() && !y.level().isClientSide && !y.profile().dangerToYukkuri() && !y.profile().houseBody()
                && !y.isNoAi() && !y.isPassenger();
    }
    /** Whether this individual may be steered right now (not mid-contact, birth, rescue, water). */
    static boolean free(YukkuriEntity y) {
        return prey(y) && !y.isCommandPlaying() && !y.operations().active() && !y.pet().busy()
                && !y.isInWaterOrBubble() && !y.isOnFire() && !y.waterDistress().active()
                && !y.family().laboring() && !y.family().birthDuty()
                && !y.family().awaitingLanding() && y.life().mealTicks() == 0;
    }
    /** Feeding does not hide danger; sleeping individuals do not perceive approaching predators. */
    static boolean alarmable(YukkuriEntity y) {
        return prey(y) && !y.life().isSleeping() && !y.isCommandPlaying() && !y.operations().active() && !y.pet().busy() && !y.isInWaterOrBubble();
    }
    static boolean kin(YukkuriEntity a, YukkuriEntity b) {
        if (!Recognition.recognizes(a, b)) return false;
        if (Bonds.currentCarePair(a, b)) return true;
        if (Bonds.eitherMate(a, b)) return true;
        if (Bonds.sameGroupId(a, b)) return true;
        var level = (ServerLevel) a.level();
        return Bonds.careKin(a, b)
                || YukkuriWorldData.get(level).affinity(a.getUUID(), b.getUUID()) >= BondLevels.AFFINITY_CLOSE;
    }

    // ---- threat -----------------------------------------------------------------------------------------------

    public static final class Threat {
        public enum Stage { NONE, WATCH, RETREAT, HIDE }
        private final YukkuriEntity y;
        /** A clever wild Reimu's den-mouth sealing (project design, see docs/FEATURES.md); idle for everyone else. */
        private final DenSeal seal;
        private Stage stage = Stage.NONE;
        private LivingEntity seen;
        private LivingEntity heard;
        private long heardUntil, alarmUntil, noticeUntil, cooldownUntil, lostSince;
        private int ticks;
        /**
         * The danger this individual last cried out about (or heard of from kin) and when. The same danger is announced
         * once per {@link #SAME_DANGER} ticks: before, a predator lingering nearby set every prey off again every 120
         * ticks and each cry re-warned all kin in earshot (about 70 alarm reactions a minute around one Remilia).
         */
        private UUID knownDanger;
        private long knownAt = Long.MIN_VALUE / 2;
        static final int SAME_DANGER = 3600, HEARD = 160;
        /** The covered stand the current flight runs to, and when it was last searched (one cover query, shared). */
        private BlockPos flightCover;
        private LivingEntity flightFrom;
        private long flightCoverAt;
        private int flightOffset;

        Threat(YukkuriEntity y) { this.y = y; seal = new DenSeal(y); }

        public Stage stage() { return stage; }
        public DenSeal seal() { return seal; }
        /** The predator (or attacker) that currently matters, or null. */
        public LivingEntity source() {
            if(y.life().isSleeping())return null;
            if (seen != null && seen.isAlive()) return seen;
            long now = y.level().getGameTime();
            return heard != null && heard.isAlive() && now < heardUntil && heard.level() == y.level() && heard.distanceToSqr(y) < 576 ? heard : null;
        }
        String activity() { return switch (stage) { case WATCH -> Behaviors.THREAT_WATCH; case RETREAT -> Behaviors.THREAT_RETREAT; case HIDE -> Behaviors.THREAT_HIDE; default -> Behaviors.IDLE; }; }
        String describe() {
            var s = source();
            return "threat:" + stage.name().toLowerCase(Locale.ROOT) + (s == null ? "" : " from #" + s.getId() + " d=" + Math.round(s.distanceTo(y))) + " seal=" + seal.describe();
        }

        private boolean menacing(YukkuriEntity p) {
            if (p == y || !p.isAlive() || !p.profile().dangerToYukkuri() || p.isPassenger()) return false;
            var owner = y.pet().owner();
            if (owner != null && owner.equals(p.pet().owner())) return false;
            if (p.predator().obedient() && HumanCommerce.protectedAnimal(y)) return false;   // an educated predator honours a protected pet
            return p.distanceToSqr(y) < 36 || y.hasLineOfSight(p);
        }
        /** Observe a real street act, independent of the resident's appearance or attitude alone. */
        private boolean menacing(HumanNpc person) {
            return person.streetThreatTo(y);
        }
        private double alarmRange() {
            var source = source();
            return y.life().personality().threatDistance(source == null ? 0 : y.life().personality().fear(source.getUUID()));
        }

        /** Every few ticks: look around, then alarm or notice. */
        void scan() {
            if (!prey(y) || y.life().isSleeping()) { seen = null; heard = null; return; }
            long now = y.level().getGameTime();
            LivingEntity best = null; double bestD = Double.MAX_VALUE;
            for (var p : y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(16, 6, 16), this::menacing)) {
                double d = p.distanceToSqr(y);
                if (d < bestD) { bestD = d; best = p; }
            }
            for (var person : y.level().getEntitiesOfClass(HumanNpc.class, y.getBoundingBox().inflate(16, 6, 16), this::menacing)) {
                double d = person.distanceToSqr(y);
                if (d < bestD) { bestD = d; best = person; }
            }
            seen = best;
            if (best == null) return;
            double d = Math.sqrt(bestD);
            // A known danger still in sight stays known: one that never leaves is announced once, not again and again.
            if (!fresh(best, now)) knownAt = now;
            boolean hunting = best instanceof YukkuriEntity predator && predator.predator().active();
            if ((d <= alarmRange() || hunting && d <= 14) && now >= alarmUntil && !y.isPanicking() && alarmable(y)) {
                alarmUntil = now + 120;
                // Fleeing again from a danger that is still close is real; crying out and warning everyone again is not.
                boolean fresh = fresh(best, now);
                if (fresh) {
                    know(best, now);
                    fright(best, 80);
                    StatusEvents.note(y, "alarm threat #" + best.getId());
                    fleeFromPredator(best);
                    warnKin(best);
                } else if (d <= 4 || hunting) fleeFromPredator(best);
            }
        }
        /** Preserve the existing panic/FleeGoal route for predators; cruel residents use this goal's direct retreat. */
        private void fleeFromPredator(LivingEntity danger) {
            if (danger instanceof YukkuriEntity predator) y.frightenFrom(predator);
        }
        /** Not yet announced (cried or heard) within {@link #SAME_DANGER} ticks. */
        private boolean fresh(LivingEntity danger, long now) {
            return !danger.getUUID().equals(knownDanger) || now - knownAt >= SAME_DANGER;
        }
        private void know(LivingEntity danger, long now) { knownDanger = danger.getUUID(); knownAt = now; }

        private void fright(LivingEntity danger, int ticks) {
            var stimulus = danger instanceof YukkuriEntity predator && predator.profile().dangerToYukkuri()
                    ? ExcitedLeak.Stimulus.PREDATOR : ExcitedLeak.Stimulus.SCARE;
            y.mind().felt(EmotionEvent.FRIGHTENED, stimulus);
            y.showReaction("surprise", "afraid", ticks);
        }

        /** Kin and friends within earshot learn of the danger even without seeing it; those who already know are not told again. */
        void warnKin(LivingEntity danger) {
            long now = y.level().getGameTime();
            for (var z : y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(12, 5, 12),
                    o -> o != y && prey(o) && kin(y, o))) z.status().reactions().threat().heard(danger, now);
        }
        void heard(LivingEntity danger, long now) { heard(danger, now, HEARD); }
        /** The ordinary threat goal waits for the meal to finish before it can steer. */
        public void cropWarning(HumanNpc resident) { heard(resident, y.level().getGameTime()); }
        /** The one that just hurt this body is remembered for the whole danger window: a bitten animal does not forget it. */
        void attackedBy(LivingEntity danger) { heard(danger, y.level().getGameTime(), SAME_DANGER); }
        private void heard(LivingEntity danger, long now, int window) {
            if(y.life().isSleeping())return;
            if (danger == y || !danger.isAlive()) return;
            heard = danger; heardUntil = now + window;
            if (!fresh(danger, now)) return;
            know(danger, now);
            if (now >= noticeUntil && stage == Stage.NONE) { noticeUntil = now + 400; fright(danger, 60); }
        }

        boolean wants() {
            var s = source();
            if (s == null || !free(y) || s instanceof HumanNpc && y.isLeashed() || y.hurtCrying() || y.grief().protecting() || y.myon().fighting() || y.isPanicking()
                    || y.family().carry().shelteredFrom(s) || y.level().getGameTime() < cooldownUntil) return false;
            return stage != Stage.NONE || s.distanceToSqr(y) <= 256 || s == heard;
        }
        boolean keeps() { return stage != Stage.NONE && free(y) && !(source() instanceof HumanNpc && y.isLeashed()) && !y.hurtCrying() && !y.grief().protecting() && !y.myon().fighting()
                && !y.isPanicking() && !y.family().carry().shelteredFrom(source()); }
        void start() {
            // Seeing the source of a warning is enough to retreat; unseen warnings retain the short listening phase.
            var danger = source();
            stage = danger != null && y.hasLineOfSight(danger) ? Stage.RETREAT : Stage.WATCH; ticks = 0; lostSince = 0;
            seal.reset();
            GameplayLog.limited(y, "threat", "start", 1200, "response started source={} stage={}",
                    danger == null ? null : danger.getUUID(), stage);
            y.life().interrupt();
            y.family().carry().startFlight("threat", danger);
            long now = y.level().getGameTime();
            var s = source();
            // Already cried out about (scan) or heard of (kin): no second start for the same danger.
            if (now >= noticeUntil && (s == null || fresh(s, now))) {
                noticeUntil = now + 400; fright(s, 60);
            }
            if (s != null) know(s, now);
        }
        void end(int cooldown) {
            y.family().carry().endFlight();
            seal.reset();
            if (stage != Stage.NONE) GameplayLog.limited(y, "threat", "end", 1200,
                    "response ended stage={} phaseTicks={} cooldown={}", stage, ticks, cooldown);
            stage = Stage.NONE; ticks = 0; cooldownUntil = y.level().getGameTime() + cooldown;
            y.getNavigation().stop();
        }

        void tick() {
            if (y.family().carry().tickFlight(source())) return;
            ticks++;
            var s = source();
            long now = y.level().getGameTime();
            if (s != null) y.getLookControl().setLookAt(s, 30, 30);
            else if (seal.active()) seal.abort("danger_lost");
            if (s == null || s.distanceToSqr(y) > 576) { if (lostSince == 0) lostSince = now; } else lostSince = 0;
            switch (stage) {
                case WATCH -> {
                    y.getNavigation().stop();
                    if (s == null) { end(60); return; }
                    if (ticks >= 25) { stage = Stage.RETREAT; ticks = 0; }
                }
                case RETREAT -> {
                    if (s == null) { stage = Stage.HIDE; ticks = 0; return; }
                    if (seal.drive(s)) return;
                    double d = s.distanceTo(y);
                    if (ticks % 10 == 1 || y.getNavigation().isDone()) retreat(s);
                    var home = y.life().homeStand();
                    if (home != null && y.distanceToSqr(Vec3.atBottomCenterOf(home)) < 2.25 && safeAt(s)) { stage = Stage.HIDE; ticks = 0; return; }
                    if (d > 20 || ticks > 400) { stage = Stage.HIDE; ticks = 0; }
                }
                case HIDE -> {
                    if (s != null && seal.drive(s)) return;
                    if (s != null && s.distanceToSqr(y) <= 256 && !safeAt(s)) { stage = Stage.RETREAT; ticks = 0; return; }
                    y.getNavigation().stop();
                    if (lostSince != 0 && now - lostSince > 80) { end(100); return; }
                    if (ticks > 1200) end(600);
                }
                default -> { }
            }
        }
        /** Reaching a keeper or a home only stays safe while the threat is not standing at that refuge. */
        private boolean safeAt(LivingEntity threat) {
            LivingEntity owner = y.pet().owner() == null ? null : y.level().getPlayerByUUID(y.pet().owner());
            if (owner == null && y.pet().owner() != null && y.level() instanceof ServerLevel sl
                    && sl.getEntity(y.pet().owner()) instanceof HumanNpc resident) owner = resident;
            if (owner != null && owner.isAlive() && !owner.isSpectator() && y.distanceToSqr(owner) <= 6.25
                    && owner.distanceToSqr(threat) > 36) return true;
            var home = y.life().homeStand();
            return home != null && y.distanceToSqr(Vec3.atBottomCenterOf(home)) < 2.25
                    && home.distSqr(threat.blockPosition()) > 25;
        }
        private void retreat(LivingEntity s) {
            LivingEntity owner = y.pet().owner() == null ? null : y.level().getPlayerByUUID(y.pet().owner());
            if (owner == null && y.pet().owner() != null && y.level() instanceof ServerLevel sl
                    && sl.getEntity(y.pet().owner()) instanceof HumanNpc resident) owner = resident; // a resident's pet runs to its keeper
            if (owner != null && owner.isAlive() && !owner.isSpectator() && owner.distanceToSqr(y) < 256
                    && owner.distanceToSqr(s) > 36) {
                if (owner.distanceToSqr(y) > 6.25) y.getNavigation().moveTo(owner, 1.2);
                else { y.getNavigation().stop(); stage = Stage.HIDE; ticks = 0; }
                return;
            }
            var home = y.life().homeStand();
            if (home != null && home.distSqr(s.blockPosition()) > 25 && y.life().navigate(home, 1.2, false)) return;
            var parent = ParentCarry.flightEscort(y, s);
            if (parent != null && y.life().navigate(parent.blockPosition(), 1.2, false)) return;
            flight(s, 10);
        }
        /** Runs for cover away from one danger, else for a dry point away; the cover is searched at most every 80 ticks. */
        public boolean flight(LivingEntity danger, int distance) {
            long now = y.level().getGameTime();
            if (flightFrom != danger || now >= flightCoverAt) {
                flightFrom = danger; flightCoverAt = now + 80;
                boolean budget = SearchBudget.take((ServerLevel) y.level());
                if (budget) flightCover = findCover(y, danger.position(), flightOffset++);
                GameplayLog.limited(y, "threat", "flight.cover", 200, "flight cover searched budget={} cover={} danger=#{} distance={}",
                        budget, flightCover, danger.getId(), Math.round(danger.distanceTo(y)));
            }
            boolean routed = flightCover != null && y.life().navigate(flightCover, 1.2, false);
            if (routed) return true;
            Vec3 away = awayFrom(y, distance, danger.position());
            boolean moved = away != null && y.getNavigation().moveTo(away.x, away.y, away.z, 1.2);
            GameplayLog.limited(y, "threat", "flight.away", 200, "flight away covered={} away={} routed={} danger=#{} distance={}",
                    flightCover != null, away, moved, danger.getId(), Math.round(danger.distanceTo(y)));
            return moved;
        }
    }

    // ---- shared ground rules for reactions ---------------------------------------------------------------------

    /**
     * The one cover query: the nearest reachable covered dry stand of this body's travel range, and farther from the
     * danger than the body itself when a danger is given. The weather goal and both flight paths use it.
     */
    static BlockPos findCover(YukkuriEntity y, Vec3 danger, int offset) {
        var level = (ServerLevel) y.level();
        var life = y.life();
        var home = life.homeStand();
        if (home != null && life.covered(home) && Sky.at(level, home) == Sky.CLEAR && life.reachable(home, false)
                && farther(home, y, danger)) return home;
        var origin = y.blockPosition();
        var candidates = new ArrayList<BlockPos>();
        int radius = TravelRange.search(y, 16);
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int x = origin.getX() + dx, z = origin.getZ() + dz;
            if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) continue;
            int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            for (int ry = origin.getY() + 1; ry >= origin.getY() - 2; ry--) {
                if (roof <= ry) continue;                          // open sky at this height
                var pos = new BlockPos(x, ry, z);
                if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
                if (!life.dryStand(pos) || !life.covered(pos) || !TravelRange.babyDestination(y, pos)
                        || !FoundShelter.streetCoverFree(y, pos)) continue;
                var owner = ShelterBlock.shelter(level, pos);
                if (owner != null && !owner.owns(y.getUUID())) continue;
                if (!farther(pos, y, danger)) continue;
                candidates.add(pos.immutable());
                break;
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        if (candidates.isEmpty()) return null;
        // Rotate through candidates: eight unreachable nearby cells must not hide a farther roof forever.
        for (int tried = 0; tried < Math.min(8, candidates.size()); tried++) {
            var pos = candidates.get(Math.floorMod(offset + tried, candidates.size()));
            if (life.reachable(pos, false)) return pos;
        }
        return null;
    }

    /** A flight point only counts when it is clearly farther from the danger than the body is standing now. */
    private static boolean farther(BlockPos pos, YukkuriEntity y, Vec3 danger) {
        return danger == null || Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(danger))
                > Math.sqrt(y.position().distanceToSqr(danger)) + FLIGHT_GAIN;
    }
    /** Blocks of extra distance from the danger a covered stand must offer to be worth hiding in. */
    private static final double FLIGHT_GAIN = 8;

    /** A random dry stand away from one danger, or null: a flight point obeys the same ground rules as any other walk. */
    public static Vec3 awayFrom(YukkuriEntity y, int distance, Vec3 danger) {
        for (int attempt = 0; attempt < 8; attempt++) {
            Vec3 away = DefaultRandomPos.getPosAway(y, distance, 4, danger);
            if (away == null) return null;
            var pos = BlockPos.containing(away);
            if (y.life().dryStand(pos) && TravelRange.babyDestination(y, pos)) return away;
        }
        return null;
    }

    /** A short retreat to a supported stand, with a local search for cover and side exits when the usual flee point fails. */
    public static Vec3 escapeStand(YukkuriEntity body, Entity danger, int distance, Set<BlockPos> tried) {
        return escapeStand(body,danger.position(),danger,distance,tried);
    }

    static Vec3 escapeStand(YukkuriEntity body, Vec3 dangerAt, Entity danger, int distance, Set<BlockPos> tried) {
        var obstacle = danger==null?null:danger.getBoundingBox();
        double currentDistance = body.position().distanceTo(dangerAt);
        var away = awayFrom(body, distance, dangerAt);
        if (away != null) {
            var stand = SurfaceApproach.floor(body, away);
            if (stand != null && body.distanceToSqr(stand) >= 1 && !tried.contains(BlockPos.containing(stand))
                    && stand.distanceTo(dangerAt) > currentDistance
                    && Double.isFinite(SurfaceApproach.routeLength(body, stand, obstacle))) return stand;
        }

        var origin = body.blockPosition();
        var level = body.level();
        var cover = new ArrayList<Vec3>();
        var open = new ArrayList<Vec3>();
        for (var cell : BlockPos.betweenClosed(origin.offset(-distance, 0, -distance), origin.offset(distance, 0, distance))) {
            int dx = cell.getX() - origin.getX(), dz = cell.getZ() - origin.getZ();
            if (dx * dx + dz * dz > distance * distance || cell.equals(origin)) continue;
            // Resolve the actual support from the current sole height, including slabs and paths.
            var stand = SurfaceApproach.floor(body, new Vec3(cell.getX() + .5, body.getY(), cell.getZ() + .5));
            if (stand == null || tried.contains(BlockPos.containing(stand)) || body.distanceToSqr(stand) < 1
                    || !TravelRange.babyDestination(body, BlockPos.containing(stand))) continue;
            var eye = stand.add(0, body.getEyeHeight(), 0);
            boolean screened = danger!=null&&level.clip(new ClipContext(danger.getEyePosition(), eye,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, danger)).getType() != HitResult.Type.MISS;
            double separation = stand.distanceTo(dangerAt);
            if (screened && separation >= currentDistance) cover.add(stand);
            else if (separation >= currentDistance + .5) open.add(stand);
        }
        for (var candidates : List.of(cover, open)) {
            candidates.sort(Comparator.comparingDouble(body::distanceToSqr));
            // Keep one blocked corner from consuming the whole route budget.
            for (int i = 0; i < Math.min(8, candidates.size()); i++) {
                var stand = candidates.get(i);
                if (Double.isFinite(SurfaceApproach.routeLength(body, stand, obstacle))) return stand;
            }
        }
        return null;
    }

    // ---- weather ----------------------------------------------------------------------------------------------

    /**
     * Rain, storm and wet fur, from what this body feels where it stands. In the open it looks for a roof (RUN, SEARCH) and a
     * thunder clap makes it flinch on the way. A wet body on a dry spot shakes the water off (SHAKE: arriving under a roof, the
     * rain stopping, out of the water after the breath). Under a roof while it rains, a cold or frightened one that is otherwise
     * idle presses beside a kin or friend who will have it, the other holding still (HUDDLE with a partner), or alone trembles in
     * rain and cowers in a storm. A clap startles a pressed pair together, and they nestle in again after it.
     */
    public static final class Weather {
        public enum Stage { NONE, RUN, WAIT, SEARCH, SHAKE, HUDDLE, FLOOD }
        /** Wetness above which a body on a dry spot shakes the water off (rain adds 3 per 20 ticks). */
        static final float SHAKE_WETNESS = 10;
        /** A body shakes once per wetting, not every time it is still a little damp: ticks after a shake before the next. */
        static final int SHAKE_REST = 400;
        /** A sheltered huddle or cower lasts at most this long, then this long passes before the next (project design). */
        static final int HUDDLE_LIMIT = 1200, HUDDLE_REST = 600;
        /** How far a sheltered body looks for someone to press against, how long it tries to reach them, and the fear that
         *  keeps two apart (as for a nuzzle). */
        static final int HUDDLE_SIGHT = 8, HUDDLE_APPROACH = 200, HUDDLE_FEAR = 60;
        /** A lone sheltered body looks around for kin or a friend this often (ticks). */
        static final int PARTNER_SCAN = 60;
        /** How far a pressed body may be pushed off its stand before it walks back in (blocks). */
        static final double HUDDLE_SLACK = .12;
        /** A lightning strike is heard this far off (blocks). */
        static final int THUNDER_HEARD = 96;
        static final int SHAKE_TICKS = EcologyPresentation.duration(Behaviors.RAIN_SHAKE),
                STARTLE_TICKS = EcologyPresentation.duration(Behaviors.THUNDER_STARTLE);
        private final YukkuriEntity y;
        private Stage stage = Stage.NONE, planned = Stage.NONE;
        private Sky sky = Sky.CLEAR;
        private BlockPos cover;
        private long cooldownUntil, searchAfter, stepAfter, shakeAfter, huddleAfter, partnerScanAfter, startled = Long.MIN_VALUE / 2;
        private int ticks;
        private boolean spoke;
        private int searchOffset;
        /** HUDDLE: who it presses beside (null alone), whether it walked over (the other holds still), the tick the pair pressed
         *  together (their shared clip clock, -1 before), which side the partner is on and the way both face. */
        private YukkuriEntity partner, unreachable;
        private boolean leads, partnerLeft, onRight;
        private Vec3 stand;
        private long pressedAt = -1;
        /** The huddle tick at which it set out toward its partner (its approach is timed from there). */
        private int approachFrom;
        private float facing;

        Weather(YukkuriEntity y) { this.y = y; }

        public Stage stage() { return stage; }
        public Sky sky() { return sky; }
        /** Behavior id of the running response (PresentationGoals.WEATHER, EcologyPresentation). */
        public String activity() {
            if (startledNow() && (stage == Stage.RUN || stage == Stage.SEARCH || stage == Stage.HUDDLE)) return Behaviors.THUNDER_STARTLE;
            return switch (stage) {
                case RUN, SEARCH, FLOOD -> Behaviors.RAIN_RUN;
                case WAIT -> Behaviors.RAIN_WAIT;
                case SHAKE -> Behaviors.RAIN_SHAKE;
                case HUDDLE -> pressedAt >= 0 ? partnerLeft ? Behaviors.RAIN_HUDDLE_LEFT : Behaviors.RAIN_HUDDLE_RIGHT
                        : partner != null ? leads ? Behaviors.RAIN_WAIT : Behaviors.IDLE
                        : Sky.open(y.level(), y.blockPosition()) == Sky.STORM ? Behaviors.THUNDER_COWER : Behaviors.RAIN_WAIT;
                default -> Behaviors.IDLE;
            };
        }
        /** The pressed pair's shared clip start, so both shiver in step; -1 when not pressed (or flinching). */
        public long huddleClock() { return stage == Stage.HUDDLE && pressedAt >= 0 && !startledNow() ? pressedAt : -1; }
        /** Pressed against its partner right now (the comfort of company, MindLife). */
        public boolean huddled() { return stage == Stage.HUDDLE && partner != null && pressedAt >= 0; }
        private boolean startledNow() { return y.level().getGameTime() - startled < STARTLE_TICKS; }
        String describe() { return "weather:" + stage.name().toLowerCase(Locale.ROOT) + " sky=" + sky.name().toLowerCase(Locale.ROOT)
                + " searchRadius=" + TravelRange.search(y, 16) + " keepDry=" + keepDry(y)
                + " hungryRain=" + (y.life().nutrition() < 20) + (cover == null ? "" : " cover=" + cover.toShortString())
                + (partner == null ? "" : " partner=#" + partner.getId() + (leads ? " leads" : " holds") + " pressed=" + (pressedAt >= 0)); }

        void scan() {
            sky = prey(y) ? Sky.at(y.level(), y.blockPosition()) : Sky.CLEAR;
            if (sky == Sky.CLEAR && stage == Stage.NONE && !y.level().isRaining()) spoke = false;
        }
        public static boolean rainAt(YukkuriEntity animal, BlockPos pos) {
            return Sky.at(animal.level(), pos).wet();
        }
        /** Hunger below this outranks staying dry: the body walks out into the rain to eat, and comes back in when fed. */
        static final float DRY_NUTRITION = 30;
        public static boolean keepDry(YukkuriEntity animal) {
            return prey(animal) && animal.level().isRaining() && animal.life().nutrition() >= DRY_NUTRITION
                    && !rainAt(animal, animal.blockPosition()) && !BehaviorContinuity.emergency(animal)
                    && !animal.family().birthDuty() && !animal.life().leavingFoundShelter();
        }
        public static boolean allowsStep(YukkuriEntity animal, BlockPos destination) {
            return !keepDry(animal) || !rainAt(animal, destination);
        }
        private boolean bothered() {
            return sky.wet() || sky == Sky.SNOW && y.life().temperature().cold();
        }
        /** Body needs are the life goal's business; this goal does not hold a hungry individual under a roof. */
        private boolean pressing() {
            var life = y.life();
            return life.nutrition() < DRY_NUTRITION || life.bladder() >= 90 || life.energy() < 10;
        }
        /** Something it is already doing comes first: a meal or other life task, its family, company, play, comfort; null when idle. */
        private static String busy(YukkuriEntity e) {
            if (!e.life().activity().equals(Behaviors.IDLE)) return "life:" + e.life().activity();
            // Nobody dozes off through a thunderstorm (the darkened sky counts as night for sleep).
            if (e.life().drowsy() && Sky.open(e.level(), e.blockPosition()) != Sky.STORM) return "drowsy";
            if (e.family().active()) return "family";
            if (e.dailyLife().active()) return "daily";
            if (e.social().active()) return "social";
            if (e.groupInteraction().active()) return "group";
            if (e.playLife().active()) return "play";
            return e.woundComfort().active() ? "comfort" : null;
        }
        /** Cold, or more uneasy than its boldness and age bear, or a timid one in a thunderstorm. */
        private boolean shaken() {
            var personality = y.life().personality();
            float bearable = MindLife.UNEASY * (.5f + personality.boldness() / 100f) * (y.family().adult() ? 1 : .75f);
            return y.life().temperature().cold() || y.mind().unease() >= bearable
                    || Sky.open(y.level(), y.blockPosition()) == Sky.STORM && personality.boldness() < Temperament.BOLDNESS_AT_35;
        }
        boolean wants() {
            if (stage != Stage.NONE) return true;
            long now = y.level().getGameTime();
            if (now < cooldownUntil || !free(y) || y.isPanicking()) return false;
            if (approachingWater(3) != null) { planned = Stage.FLOOD; return true; }
            if (bothered()) {
                if (pressing()) return false;
                if (now >= searchAfter) {
                    if (!SearchBudget.take((ServerLevel) y.level())) return false;
                    cover = findCover();
                    searchAfter = now + 80;
                }
                planned = cover != null ? Stage.RUN : Stage.SEARCH;
                return true;
            }
            if (BehaviorContinuity.emergency(y) || y.life().isSleeping() || !y.onGround()) return false;
            if (y.life().wetness() >= SHAKE_WETNESS && now >= shakeAfter) { planned = Stage.SHAKE; return true; }
            if (now < huddleAfter || pressing() || !Sky.sheltered(y.level(), y.blockPosition()) || !shaken()) return false;
            var busy = busy(y);
            if (busy != null) {
                GameplayLog.limited(y, "weather", "huddle.busy." + busy, 1200, "sheltered and uneasy but busy={} unease={} cold={}",
                        busy, Math.round(y.mind().unease()), y.life().temperature().cold());
                return false;
            }
            planned = Stage.HUDDLE;
            return true;
        }
        boolean keeps() {
            if (stage == Stage.NONE) return false;
            if (stage == Stage.WAIT && !rainAt(y, y.blockPosition())) return false;
            if (!free(y) || y.isPanicking()) return false;
            if (stage == Stage.FLOOD) return true;
            if (pressing()) return false;
            return stage == Stage.SHAKE || stage == Stage.HUDDLE || y.level().isRaining();
        }
        void start() {
            ticks = 0;
            stage = planned;
            switch (stage) {
                case SHAKE -> GameplayLog.limited(y, "weather", "shake.start", 600, "shaking water off wetness={} after={}",
                        y.life().wetness(), y.waterDistress().closedWithin(200) ? "water" : y.level().isRaining() ? "rain" : "rain_stopped");
                case HUDDLE -> beginHuddle();
                default -> {
                    y.life().wakeForDanger("weather");
                    y.life().interrupt();
                    if (stage != Stage.FLOOD || approachingWater(1) == null)
                        y.family().carry().startFlight(stage == Stage.FLOOD ? "rising_water" : "weather", null);
                    GameplayLog.limited(y, "weather", "start", 1200, "shelter response started sky={} stage={} cover={}", sky, stage, cover);
                    stepAfter = 0;
                    if (!spoke) {
                        spoke = true;
                        boolean storm = sky == Sky.STORM;
                        y.mind().felt(EmotionEvent.CHILLED); y.showReaction(storm ? "surprise" : "neutral", "cold", 80);
                    }
                }
            }
        }
        void end(int cooldown) {
            y.family().carry().endFlight();
            if (stage == Stage.SHAKE && ticks < SHAKE_TICKS) shakeAfter = y.level().getGameTime() + SHAKE_TICKS; // cut off: it shakes again soon
            if (stage == Stage.HUDDLE) {
                GameplayLog.limited(y, "weather", "huddle.end", 600, "huddle ended partner={} pressed={} ticks={} unease={} cold={}",
                        partner == null ? null : partner.getUUID(), pressedAt >= 0, ticks, Math.round(y.mind().unease()), y.life().temperature().cold());
                huddleAfter = y.level().getGameTime() + HUDDLE_REST;
                partner = null; pressedAt = -1;
            } else if (stage != Stage.NONE) GameplayLog.limited(y, "weather", "end", 1200,
                    "shelter response ended stage={} phaseTicks={} cover={} cooldown={}", stage, ticks, cover, cooldown);
            stage = Stage.NONE; ticks = 0; cover = null;
            cooldownUntil = y.level().getGameTime() + cooldown;
            y.getNavigation().stop();
        }

        private void searchOnFoot() {
            stage = Stage.SEARCH; ticks = 0; cover = null; stepAfter = 0;
            y.getNavigation().stop();
        }

        private BlockPos findCover() {
            var parent = ParentCarry.flightEscort(y, null);
            if (parent != null) {
                var shelter = parent.status().reactions().weather.cover;
                if (shelter != null && y.life().reachable(shelter, false)) return shelter;
            }
            return Reactions.findCover(y, null, searchOffset++);
        }

        /** Water at foot level flowing toward this dry stand. Still pools and water below a bank do not start a flight. */
        private BlockPos approachingWater(int range) {
            var origin = y.blockPosition();
            var level = y.level();
            for (int distance = 1; distance <= range; distance++) {
                for (var direction : Direction.Plane.HORIZONTAL) {
                    var at = origin.relative(direction, distance);
                    var fluid = level.getFluidState(at);
                    if (!fluid.is(FluidTags.WATER)) continue;
                    var flow = fluid.getFlow(level, at);
                    if (flow.x * direction.getStepX() + flow.z * direction.getStepZ() < 0) return at;
                }
            }
            return null;
        }

        void tick() {
            if ((stage == Stage.RUN || stage == Stage.SEARCH) && y.family().carry().tickFlight(null)) return;
            ticks++;
            var level = (ServerLevel) y.level();
            var life = y.life();
            boolean raining = level.isRaining();
            // A thunder clap: it flinches where it is, on the run or pressed under a roof.
            if (startledNow() && stage != Stage.SHAKE) { SurfaceApproach.halt(y); return; }
            // Reaching any roof ends the run, whether or not it was the chosen one. A soaked body shakes the rain off first.
            if ((stage == Stage.RUN || stage == Stage.SEARCH) && raining && Sky.at(level, y.blockPosition()) == Sky.CLEAR
                    && life.covered(y.blockPosition()) && FoundShelter.streetCoverFree(y, y.blockPosition()) && ticks > 20) {
                y.getNavigation().stop();
                y.family().carry().reachedCover();
                logStreetCover(level);
                stage = life.wetness() >= SHAKE_WETNESS ? Stage.SHAKE : Stage.WAIT;
                ticks = 0;
                if (stage == Stage.SHAKE) GameplayLog.limited(y, "weather", "shake.arrival", 1200,
                        "shaking the rain off on arrival; wetness={} cover={}", life.wetness(), cover);
                return;
            }
            switch (stage) {
                case FLOOD -> {
                    var water = approachingWater(3);
                    if (water == null) {
                        y.family().carry().reachedCover();
                        end(40);
                        return;
                    }
                    if (approachingWater(1) != null) y.family().carry().endFlight();
                    else if (y.family().carry().tickFlight(null)) return;
                    if (ticks % 10 == 1 || y.getNavigation().isDone()) {
                        var away = awayFrom(y, 8, Vec3.atCenterOf(water));
                        if (away != null) life.navigate(BlockPos.containing(away), 1.4, false);
                    }
                }
                case RUN -> {
                    if (cover == null || !life.dryStand(cover) || !FoundShelter.streetCoverFree(y, cover)) {
                        searchOnFoot(); return;
                    }
                    Vec3 spot = Vec3.atBottomCenterOf(cover);
                    double reach = y.distanceToSqr(spot);
                    // The path stops up to half a block short; the last step is walked on the supported surface.
                    if ((reach < .16 || reach < 2.25 && Sky.at(level, y.blockPosition()) == Sky.CLEAR)
                            && FoundShelter.streetCoverFree(y, y.blockPosition())) {
                        logStreetCover(level);
                        SurfaceApproach.halt(y); y.family().carry().reachedCover(); stage = Stage.WAIT; ticks = 0; return;
                    }
                    if (reach < 6.25) SurfaceApproach.walk(y, spot, 1.3);
                    else if (ticks % 10 == 1 || y.getNavigation().isDone()) {
                        if (!life.navigate(cover, sky == Sky.STORM ? 1.7 : 1.4, false)) { searchOnFoot(); return; }
                    }
                    if (ticks > 1000) searchOnFoot();
                }
                case WAIT -> {
                    y.getNavigation().stop();
                    if (raining && Sky.at(level, y.blockPosition()) != Sky.CLEAR && ticks > 10) {
                        if (cover == null) searchOnFoot(); else { stage = Stage.RUN; ticks = 0; }
                        return;
                    }
                    if (!raining) { stage = Stage.SHAKE; ticks = 0; }
                    else end(0); // dry-area planning resumes (a shaken body then huddles); navigation preserves the rain preference
                }
                case SEARCH -> {
                    long now = level.getGameTime();
                    if (now >= searchAfter && SearchBudget.take(level)) {
                        cover = findCover();
                        searchAfter = now + 80;
                        if (cover != null) { stage = Stage.RUN; ticks = 0; return; }
                    }
                    if (now >= stepAfter && (y.getNavigation().isDone() || ticks % 80 == 0)) {
                        stepAfter = now + 20;
                        var next = DefaultRandomPos.getPos(y, TravelRange.search(y, 14), 3);
                        if (next != null && y.distanceToSqr(next) >= 4 && life.dryStand(BlockPos.containing(next)) && TravelRange.babyDestination(y, BlockPos.containing(next))) {
                            life.navigate(BlockPos.containing(next), sky == Sky.STORM ? 1.7 : 1.4, false);
                        }
                    }
                    if (!raining) { stage = Stage.SHAKE; ticks = 0; }
                }
                case SHAKE -> {
                    SurfaceApproach.halt(y);
                    if (ticks < SHAKE_TICKS) return;
                    float before = life.shakeOff();
                    shakeAfter = level.getGameTime() + SHAKE_REST;
                    GameplayLog.limited(y, "weather", "shake.done", 600, "shook the water off wetness={} -> {}", before, life.wetness());
                    end(0);
                }
                case HUDDLE -> huddle(level);
                default -> { }
            }
        }

        private void logStreetCover(ServerLevel level) {
            var at = y.blockPosition();
            var roof = FoundShelter.roofKind(level, at);
            if (roof != null && y.onGround() && Sky.at(level, at) == Sky.CLEAR)
                GameplayLog.event(y, "weather", "street rain cover reached spot={} at={}", roof, at.toShortString());
        }

        // ---- huddle --------------------------------------------------------------------------------------------------

        /** Who it would press beside, or why the nearest one in sight is not that one (for the log). */
        private record Seek(YukkuriEntity partner, String nearest) {}
        /** Kin first, then the most trusted friend in sight under the same rain. Kin are always sought; a friend only when it
         *  feels like company this time (attachment, as for any outing together). Not the one it could not reach this time. */
        private Seek seekPartner() {
            boolean company = !y.family().adult() || y.getRandom().nextFloat() < y.life().personality().companionshipProbability();
            YukkuriEntity best = null;
            int bestScore = Integer.MIN_VALUE;
            String nearest = "none";
            double nearestDistance = Double.MAX_VALUE;
            for (var other : y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(HUDDLE_SIGHT, 2, HUDDLE_SIGHT),
                    o -> o != y && o != unreachable && y.hasLineOfSight(o))) {
                boolean kin = kin(y, other);
                var refusal = !kin && !company ? "no_company" : refusal(other, y);
                if (refusal != null) {
                    if (y.distanceToSqr(other) < nearestDistance) { nearestDistance = y.distanceToSqr(other); nearest = other.getUUID() + ":" + refusal; }
                    continue;
                }
                int score = (kin ? 1000 : 0) + y.life().personality().trust(other.getUUID()) - (int) y.distanceTo(other);
                if (score > bestScore) { bestScore = score; best = other; }
            }
            return new Seek(best, nearest);
        }
        /** The other's own choice: sheltered and otherwise idle, nobody pressed against it yet, and it has this one (kin, or a
         *  trusted friend), neither afraid of the other. Null when it welcomes the asker, else why not. */
        private static String refusal(YukkuriEntity other, YukkuriEntity asker) {
            var theirs = other.status().reactions().weather;
            if (!prey(other) || !free(other) || !other.onGround() || other.life().isSleeping() || other.isPanicking()) return "unavailable";
            var busy = busy(other);
            if (busy != null) return "busy_" + busy;
            if (theirs.stage != Stage.NONE && (theirs.stage != Stage.HUDDLE || theirs.partner != null)) return "weather_" + theirs.stage;
            if (!Sky.sheltered(other.level(), other.blockPosition())) return "not_sheltered";
            if (!other.life().canRecognize(asker) || !asker.life().canRecognize(other)) return "unrecognized";
            if (other.life().personality().fear(asker.getUUID()) >= HUDDLE_FEAR || asker.life().personality().fear(other.getUUID()) >= HUDDLE_FEAR)
                return "afraid";
            return kin(other, asker) || other.life().personality().trust(asker.getUUID()) > 0 ? null : "stranger";
        }
        private void beginHuddle() {
            pressedAt = -1; partner = unreachable = null;
            var seek = seekPartner();
            partnerScanAfter = y.level().getGameTime() + PARTNER_SCAN;
            if (seek.partner() != null) { pair(seek.partner()); return; }
            GameplayLog.limited(y, "weather", "hunker.start", 600, "sheltered alone pose={} unease={} cold={} boldness={} attachment={} nearest={}",
                    Sky.open(y.level(), y.blockPosition()) == Sky.STORM ? "cower" : "tremble", Math.round(y.mind().unease()),
                    y.life().temperature().cold(), y.life().personality().boldness(), y.life().personality().attachment(), seek.nearest());
        }
        /** It goes to press beside {@code other}, who holds still for it. */
        private void pair(YukkuriEntity other) {
            partner = other; leads = true; stand = null; facing = other.getYRot(); pressedAt = -1; approachFrom = ticks;
            var theirs = other.status().reactions().weather;
            theirs.stage = Stage.HUDDLE; theirs.ticks = 0; theirs.partner = y; theirs.leads = false; theirs.pressedAt = -1;
            GameplayLog.limited(y, "weather", "huddle.start", 600,
                    "huddle sought partner={} kin={} trust={} distance={} unease={} cold={} storm={}", other.getUUID(), kin(y, other),
                    y.life().personality().trust(other.getUUID()), Math.round(y.distanceTo(other) * 10) / 10.0,
                    Math.round(y.mind().unease()), y.life().temperature().cold(), Sky.open(y.level(), y.blockPosition()) == Sky.STORM);
        }
        /** Both still hold each other as partners, close enough to keep it up. */
        private boolean paired() {
            if (partner == null || !partner.isAlive() || partner.isRemoved() || partner.level() != y.level()
                    || y.distanceToSqr(partner) > (HUDDLE_SIGHT + 2) * (HUDDLE_SIGHT + 2)) return false;
            var theirs = partner.status().reactions().weather;
            return theirs.stage == Stage.HUDDLE && theirs.partner == y && free(partner);
        }
        private void huddle(ServerLevel level) {
            long now = level.getGameTime();
            if (!level.isRaining()) {
                // The rain is over: a wet body shakes it off, a dry one goes about its day.
                if (y.life().wetness() >= SHAKE_WETNESS && now >= shakeAfter) {
                    GameplayLog.limited(y, "weather", "huddle.end", 600, "huddle ended partner={} pressed={} ticks={} reason=rain_stopped",
                            partner == null ? null : partner.getUUID(), pressedAt >= 0, ticks);
                    partner = null; pressedAt = -1; huddleAfter = now + HUDDLE_REST;
                    stage = Stage.SHAKE; ticks = 0;
                } else end(0);
                return;
            }
            if (Sky.at(level, y.blockPosition()).wet() && ticks > 20) { end(0); return; } // in the rain again: look for a roof
            if (partner != null && !paired()) {
                // The other left (its own need, a fright): a leader still shaken stays put alone, the one that held still is free.
                partner = null; pressedAt = -1;
                if (!leads) { end(0); return; }
            }
            if (ticks > HUDDLE_LIMIT || leads && ticks > 100 && !shaken()) { end(0); return; }
            if (partner == null) {
                // Alone it keeps an eye out: a kin or friend who comes in out of the rain or finishes what it was doing is sought.
                SurfaceApproach.halt(y);
                if (now < partnerScanAfter) return;
                partnerScanAfter = now + PARTNER_SCAN;
                var seek = seekPartner();
                if (seek.partner() == null) return;
                pair(seek.partner());
            }
            if (leads && pressedAt >= 0 && y.position().subtract(stand).horizontalDistanceSqr() > HUDDLE_SLACK * HUDDLE_SLACK)
                pressedAt = partner.status().reactions().weather.pressedAt = -1; // pushed off its place: it walks back in
            if (leads && pressedAt < 0) {
                // Someone else sitting on its place sends it to the partner's other side.
                if (stand == null || occupied(level, stand)) stand = chooseStand(level);
                if (stand == null || ticks - approachFrom > HUDDLE_APPROACH) {
                    GameplayLog.limited(y, "weather", "huddle.stand", 600,
                            "huddle stand unavailable partner={} reason={} ticks={} distance={} stand={}", partner.getUUID(),
                            stand == null ? "no_free_side" : "not_reached", ticks - approachFrom, Math.round(y.distanceTo(partner) * 10) / 10.0, stand);
                    // The other is released by its own next check; this one trembles alone and looks to someone else this time.
                    unreachable = partner; partner = null; stand = null;
                    SurfaceApproach.halt(y);
                    return;
                }
                if (!SurfaceApproach.walkAround(y, stand, partner, .8)) return;
                press(now);
            }
            SurfaceApproach.halt(y);
            if (pressedAt >= 0) SurfaceApproach.face(y, facing);
        }
        /** The nearer of the two sheltered stands pressed against the partner's sides, both facing the way it already faces. */
        private Vec3 chooseStand(ServerLevel level) {
            Vec3 best = null;
            for (boolean right : new boolean[] {true, false}) {
                var at = SurfaceApproach.floor(y, SideContact.beside(y, partner, facing, right));
                if (at == null || Math.abs(at.y - partner.getY()) > .5 || !Sky.sheltered(level, BlockPos.containing(at)) || occupied(level, at)) continue;
                if (best == null || y.distanceToSqr(at) < y.distanceToSqr(best)) { best = at; onRight = right; }
            }
            return best;
        }
        /** Another body (not this one or its partner) sits where this one would stand at {@code at}. */
        private boolean occupied(ServerLevel level, Vec3 at) {
            var body = y.getBoundingBox().move(at.subtract(y.position())).deflate(.005);
            return !level.getEntitiesOfClass(LivingEntity.class, body, o -> o != y && o != partner && o.isAlive()).isEmpty();
        }
        /** Pressed together at its stand: on the partner's right it has the partner on its left, and both face the same way. */
        private void press(long now) {
            var theirs = partner.status().reactions().weather;
            partnerLeft = onRight; theirs.partnerLeft = !onRight; theirs.facing = facing;
            pressedAt = theirs.pressedAt = now;
            var to = partner.position().subtract(y.position());
            GameplayLog.limited(y, "weather", "huddle.pressed", 600, "huddle pressed partner={} side={} centres={} afterTicks={}",
                    partner.getUUID(), partnerLeft ? "left" : "right", Math.round(to.horizontalDistance() * 100) / 100.0, ticks);
        }
        /** A thunder clap it heard: it flinches; a pressed pair, startled together, nestles in again after it. */
        void clap(long now, double distance) {
            if (!prey(y) || y.life().isSleeping() || y.isInWaterOrBubble()) return;
            startled = now;
            y.mind().felt(EmotionEvent.FRIGHTENED, ExcitedLeak.Stimulus.LOUD_NOISE);
            if (pressedAt >= 0) pressedAt = now + STARTLE_TICKS;
            if (stage == Stage.NONE) y.showReaction("surprise", "thunder", STARTLE_TICKS);
            GameplayLog.limited(y, "weather", "thunder", 600, "thunder clap heard distance={} stage={} partner={}",
                    Math.round(distance), stage, partner == null ? null : partner.getUUID());
        }
    }

    /** Every yukkuri within earshot of a lightning strike hears the clap (CommonRegistration). */
    public static void lightning(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof LightningBolt bolt) || !(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, bolt.getBoundingBox().inflate(Weather.THUNDER_HEARD, 48, Weather.THUNDER_HEARD),
                Reactions::prey)) y.status().reactions().weather.clap(now, Math.sqrt(y.distanceToSqr(bolt)));
    }

    // ---- goals ------------------------------------------------------------------------------------------------

    /** Emergency tier: stop what you are doing, watch, back away, hide. */
    public static final class ThreatGoal extends Goal {
        private final YukkuriEntity y;
        public ThreatGoal(YukkuriEntity y) { this.y = y; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        private Threat threat() { return y.status().reactions().threat; }
        @Override public boolean canUse() { return threat().wants(); }
        @Override public boolean canContinueToUse() { return threat().keeps(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { threat().start(); }
        @Override public void tick() { threat().tick(); }
        @Override public void stop() { if (threat().stage != Threat.Stage.NONE) threat().end(60); }
    }

    /** Needs tier: search on foot, reach dry ground, then release routine movement. */
    public static final class WeatherGoal extends Goal {
        private final YukkuriEntity y;
        public WeatherGoal(YukkuriEntity y) { this.y = y; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        private Weather weather() { return y.status().reactions().weather; }
        @Override public boolean canUse() { return weather().wants(); }
        @Override public boolean canContinueToUse() { return weather().keeps(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { if (weather().stage == Weather.Stage.NONE) weather().start(); }
        @Override public void tick() { weather().tick(); }
        @Override public void stop() { if (weather().stage != Weather.Stage.NONE) weather().end(40); }
    }
}
