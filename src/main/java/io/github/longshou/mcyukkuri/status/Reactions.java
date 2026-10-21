package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.TravelRange;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SearchBudget;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.Heightmap;

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
    public boolean active() { return threat.stage != Threat.Stage.NONE || weather.stage != Weather.Stage.NONE; }
    /** Behavior id for the shared presentation chain (see EcologyPresentation). */
    public String activity() { return threat.stage != Threat.Stage.NONE ? threat.activity() : weather.activity(); }
    public String describe() {
        return threat.stage != Threat.Stage.NONE ? threat.describe() : weather.stage != Weather.Stage.NONE ? weather.describe() : "none";
    }
    void scan() { threat.scan(); weather.scan(); }
    void stop() { threat.stage = Threat.Stage.NONE; weather.stage = Weather.Stage.NONE; }

    /** Kin hear the alarm; nearby witnesses also react when they see a person inflict harm. */
    void hurtBy(DamageSource source) {
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker == entity) return;
        threat.warnKin(attacker);
        if (!(attacker instanceof Player || attacker instanceof HumanNpc)) return;
        long now = entity.level().getGameTime();
        for (var witness : entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(8, 4, 8),
                y -> y != entity && alarmable(y) && y.distanceToSqr(entity) <= 64
                        && y.hasLineOfSight(entity) && y.hasLineOfSight(attacker))) {
            witness.status().reactions().threat().heard(attacker, now);
        }
    }

    // ---- shared predicates -------------------------------------------------------------------------------------

    /** An ordinary free-living or kept individual: not a predator, not a house yukkuri, not an aquatic crew member. */
    static boolean prey(YukkuriEntity y) {
        return y.isAlive() && !y.level().isClientSide && !y.profile().dangerToYukkuri() && !y.profile().houseBody()
                && !y.isNoAi() && !y.isPassenger();
    }
    /** Whether this individual may be steered right now (not mid-contact, birth, rescue, water). */
    static boolean free(YukkuriEntity y) {
        return prey(y) && !y.isCommandPlaying() && !y.operations().active() && !y.pet().busy()
                && !y.isInWaterOrBubble() && !y.isOnFire() && !y.waterDistress().active()
                && !y.family().laboring() && !y.family().birthDuty() && !y.family().braidRescueActive()
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
        static final int SAME_DANGER = 3600;

        Threat(YukkuriEntity y) { this.y = y; }

        public Stage stage() { return stage; }
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
            return "threat:" + stage.name().toLowerCase(Locale.ROOT) + (s == null ? "" : " from #" + s.getId() + " d=" + Math.round(s.distanceTo(y)));
        }

        private boolean menacing(YukkuriEntity p) {
            if (p == y || !p.isAlive() || !p.profile().dangerToYukkuri() || p.isPassenger()) return false;
            var owner = y.pet().owner();
            if (owner != null && owner.equals(p.pet().owner())) return false;
            if (p.predator().obedient() && HumanCommerce.protectedAnimal(y)) return false;   // an educated predator honours a protected pet
            return p.distanceToSqr(y) < 36 || y.hasLineOfSight(p);
        }
        /** A cruel resident is frightening only while its existing torment rules permit harm to this individual. */
        private boolean menacing(HumanNpc person) {
            return person.isAlive() && person.canTorment(y)
                    && (person.distanceToSqr(y) < 36 || y.hasLineOfSight(person));
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
                    y.mind().felt(EmotionEvent.FRIGHTENED); y.showReaction("surprise", "afraid", 80);
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

        /** Kin and friends within earshot learn of the danger even without seeing it; those who already know are not told again. */
        void warnKin(LivingEntity danger) {
            long now = y.level().getGameTime();
            for (var z : y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(12, 5, 12),
                    o -> o != y && prey(o) && kin(y, o))) z.status().reactions().threat().heard(danger, now);
        }
        void heard(LivingEntity danger, long now) {
            if(y.life().isSleeping())return;
            if (danger == y || !danger.isAlive()) return;
            heard = danger; heardUntil = now + 160;
            if (!fresh(danger, now)) return;
            know(danger, now);
            if (now >= noticeUntil && stage == Stage.NONE) { noticeUntil = now + 400; y.mind().felt(EmotionEvent.FRIGHTENED); y.showReaction("surprise", "afraid", 60); }
        }

        boolean wants() {
            var s = source();
            if (s == null || !free(y) || y.isPanicking() || y.level().getGameTime() < cooldownUntil) return false;
            return stage != Stage.NONE || s.distanceToSqr(y) <= 256 || s == heard;
        }
        boolean keeps() { return stage != Stage.NONE && free(y) && !y.isPanicking(); }
        void start() {
            // Seeing the source of a warning is enough to retreat; unseen warnings retain the short listening phase.
            var danger = source();
            stage = danger != null && y.hasLineOfSight(danger) ? Stage.RETREAT : Stage.WATCH; ticks = 0; lostSince = 0;
            GameplayLog.limited(y, "threat", "start", 1200, "response started source={} stage={}",
                    danger == null ? null : danger.getUUID(), stage);
            y.life().interrupt();
            long now = y.level().getGameTime();
            var s = source();
            // Already cried out about (scan) or heard of (kin): no second start for the same danger.
            if (now >= noticeUntil && (s == null || fresh(s, now))) {
                noticeUntil = now + 400; y.mind().felt(EmotionEvent.FRIGHTENED); y.showReaction("surprise", "afraid", 60);
            }
            if (s != null) know(s, now);
        }
        void end(int cooldown) {
            if (stage != Stage.NONE) GameplayLog.limited(y, "threat", "end", 1200,
                    "response ended stage={} phaseTicks={} cooldown={}", stage, ticks, cooldown);
            stage = Stage.NONE; ticks = 0; cooldownUntil = y.level().getGameTime() + cooldown;
            y.getNavigation().stop();
        }

        void tick() {
            ticks++;
            var s = source();
            long now = y.level().getGameTime();
            if (s != null) y.getLookControl().setLookAt(s, 30, 30);
            if (s == null || s.distanceToSqr(y) > 576) { if (lostSince == 0) lostSince = now; } else lostSince = 0;
            switch (stage) {
                case WATCH -> {
                    y.getNavigation().stop();
                    if (s == null) { end(60); return; }
                    if (ticks >= 25) { stage = Stage.RETREAT; ticks = 0; }
                }
                case RETREAT -> {
                    if (s == null) { stage = Stage.HIDE; ticks = 0; return; }
                    double d = s.distanceTo(y);
                    if (ticks % 10 == 1 || y.getNavigation().isDone()) retreat(s);
                    var home = y.life().homeStand();
                    if (home != null && y.distanceToSqr(Vec3.atBottomCenterOf(home)) < 2.25 && safeAt(s)) { stage = Stage.HIDE; ticks = 0; return; }
                    if (d > 20 || ticks > 400) { stage = Stage.HIDE; ticks = 0; }
                }
                case HIDE -> {
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
                if (owner.distanceToSqr(y) > 6.25) y.getNavigation().moveTo(owner, 1.5);
                else { y.getNavigation().stop(); stage = Stage.HIDE; ticks = 0; }
                return;
            }
            var home = y.life().homeStand();
            if (home != null && home.distSqr(s.blockPosition()) > 25 && y.life().navigate(home, 1.4, false)) return;
            Vec3 away = DefaultRandomPos.getPosAway(y, 10, 4, s.position());
            if (away != null) y.getNavigation().moveTo(away.x, away.y, away.z, 1.4);
        }
    }

    // ---- weather ----------------------------------------------------------------------------------------------

    public static final class Weather {
        public enum Stage { NONE, RUN, WAIT, SEARCH, SHAKE }
        private final YukkuriEntity y;
        private Stage stage = Stage.NONE;
        private Sky sky = Sky.CLEAR;
        private BlockPos cover;
        private long cooldownUntil, searchAfter, stepAfter;
        private int ticks;
        private boolean spoke;
        private int searchOffset;

        Weather(YukkuriEntity y) { this.y = y; }

        public Stage stage() { return stage; }
        public Sky sky() { return sky; }
        String activity() {
            return switch (stage) { case RUN, SEARCH -> Behaviors.RAIN_RUN; case WAIT -> Behaviors.RAIN_WAIT;
                     case SHAKE -> Behaviors.RAIN_SHAKE; default -> Behaviors.IDLE; };
        }
        String describe() { return "weather:" + stage.name().toLowerCase(Locale.ROOT) + " sky=" + sky.name().toLowerCase(Locale.ROOT)
                + " searchRadius=" + TravelRange.search(y, 16) + " keepDry=" + keepDry(y)
                + " hungryRain=" + (y.life().nutrition() < 20) + (cover == null ? "" : " cover=" + cover.toShortString()); }

        void scan() {
            sky = prey(y) ? Sky.at(y.level(), y.blockPosition()) : Sky.CLEAR;
            if (sky == Sky.CLEAR && stage == Stage.NONE && !y.level().isRaining()) spoke = false;
        }
        public static boolean rainAt(YukkuriEntity animal, BlockPos pos) {
            return Sky.at(animal.level(), pos).wet();
        }
        public static boolean keepDry(YukkuriEntity animal) {
            return prey(animal) && animal.level().isRaining() && animal.life().nutrition() >= 20
                    && !rainAt(animal, animal.blockPosition()) && !BehaviorContinuity.emergency(animal)
                    && !animal.family().birthDuty() && !animal.family().braidRescueActive();
        }
        public static boolean allowsStep(YukkuriEntity animal, BlockPos destination) {
            return !keepDry(animal) || !rainAt(animal, destination);
        }
        private boolean bothered() {
            return sky.wet() || sky == Sky.SNOW && y.life().temperature().cold();
        }
        /** Body emergencies are the life goal's business; this goal does not hold a starving individual in place. */
        private boolean pressing() {
            var life = y.life();
            return life.nutrition() < 20 || life.bladder() >= 90 || life.energy() < 10;
        }
        boolean wants() {
            if (stage != Stage.NONE) return true;
            long now = y.level().getGameTime();
            if (!bothered() || now < cooldownUntil || !free(y) || y.isPanicking() || pressing()) return false;
            if (now >= searchAfter) {
                if (!SearchBudget.take((ServerLevel) y.level())) return false;
                cover = findCover();
                searchAfter = now + 80;
            }
            return true;
        }
        boolean keeps() {
            if (stage == Stage.NONE) return false;
            if (stage == Stage.WAIT && !rainAt(y, y.blockPosition())) return false;
            if (!free(y) || y.isPanicking() || pressing()) return false;
            return stage == Stage.SHAKE || y.level().isRaining();
        }
        void start() {
            ticks = 0;
            y.life().wakeForDanger("weather");
            y.life().interrupt();
            stage = cover != null ? Stage.RUN : Stage.SEARCH;
            GameplayLog.limited(y, "weather", "start", 1200, "shelter response started sky={} stage={} cover={}", sky, stage, cover);
            stepAfter = 0;
            if (!spoke) {
                spoke = true;
                boolean storm = sky == Sky.STORM;
                y.mind().felt(EmotionEvent.CHILLED); y.showReaction(storm ? "surprise" : "neutral", "cold", 80);
            }
        }
        void end(int cooldown) {
            if (stage != Stage.NONE) GameplayLog.limited(y, "weather", "end", 1200,
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
            var level = (ServerLevel) y.level();
            var life = y.life();
            var home = life.homeStand();
            if (home != null && life.covered(home) && Sky.at(level, home) == Sky.CLEAR && life.reachable(home, false)) return home;
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
                    if (!life.dryStand(pos) || !life.covered(pos) || !TravelRange.babyDestination(y, pos)) continue;
                    var owner = ShelterBlock.shelter(level, pos);
                    if (owner != null && !owner.owns(y.getUUID())) continue;
                    candidates.add(pos.immutable());
                    break;
                }
            }
            candidates.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
            if (!candidates.isEmpty()) {
                // Rotate through candidates: eight unreachable nearby cells must not hide a farther roof forever.
                for (int tried = 0; tried < Math.min(8, candidates.size()); tried++) {
                    var pos = candidates.get(Math.floorMod(searchOffset++, candidates.size()));
                    if (life.reachable(pos, false)) return pos;
                }
            }
            return null;
        }

        void tick() {
            ticks++;
            var level = (ServerLevel) y.level();
            var life = y.life();
            boolean raining = level.isRaining();
            // Reaching any roof ends the run, whether or not it was the chosen one.
            if ((stage == Stage.RUN || stage == Stage.SEARCH) && raining && Sky.at(level, y.blockPosition()) == Sky.CLEAR
                    && life.covered(y.blockPosition()) && ticks > 20) { y.getNavigation().stop(); stage = Stage.WAIT; ticks = 0; return; }
            switch (stage) {
                case RUN -> {
                    if (cover == null || !life.dryStand(cover)) { searchOnFoot(); return; }
                    Vec3 spot = Vec3.atBottomCenterOf(cover);
                    double reach = y.distanceToSqr(spot);
                    // The path stops up to half a block short; the last step is walked on the supported surface.
                    if (reach < .16 || reach < 2.25 && Sky.at(level, y.blockPosition()) == Sky.CLEAR) {
                        SurfaceApproach.halt(y); stage = Stage.WAIT; ticks = 0; return;
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
                    else end(0); // dry-area planning resumes; navigation preserves the rain preference
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
                    y.getNavigation().stop();
                    if (ticks >= 40) end(0);
                }
                default -> { }
            }
        }
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
