package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.body.BodyHit;
import io.github.longshou.mcyukkuri.entity.component.InterruptContext;
import io.github.longshou.mcyukkuri.entity.component.InterruptPhase;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.social.ProtectivePuff;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.mind.ExcitedLeak;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.NeedWarnings.Need;
import io.github.longshou.mcyukkuri.life.TravelRange;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.Encounter;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.intent.Attempts;
import io.github.longshou.mcyukkuri.intent.WorldSignature;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
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
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.social.HookRescueGoal;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
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
    private long nextNeedTell;
    private int toldNeeds;
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
        return threat.stage != Threat.Stage.NONE ? threat.describe() : weather.stage != Weather.Stage.NONE ? weather.describe()
                : threat.seal.active() ? "den seal=" + threat.seal.describe() : "none";
    }
    void scanWeatherAndNeeds() {
        weather.scan();
        long now = entity.level().getGameTime();
        int requested = switch (entity.pet().care().requests().pending()) {
            case FOOD -> Need.HUNGER.bit();
            case WATER -> Need.THIRST.bit();
            case WARM -> Need.COLD.bit();
            case null -> 0;
            case NUZZLE, PLAY, SNACK, WALK -> 0;
        };
        var needs = entity.life().needWarnings(toldNeeds | requested);
        toldNeeds &= needs.mask();
        if (now < nextNeedTell || entity.hasReaction() || entity.speechEnd() > now || !allowsNeedTell()) return;
        if (needs.leading() != null) {
            EcologyPresentation.tell(entity, needs);
            toldNeeds |= needs.leading().bit();
            nextNeedTell = now + 160; // A short cue, then eight seconds for the underlying activity.
        }
    }

    public boolean allowsNeedTell() {
        return freeToReact(entity) && !active() && !entity.isPanicking() && entity.hurtTime == 0
                && !entity.body().dying() && !entity.body().condition().needsPainExpression()
                && !entity.life().protectsStep() && !entity.life().isSleeping() && !entity.life().temperature().active()
                && !entity.family().active() && !entity.childhood().active() && !entity.agitation().active()
                && entity.mind().leakFace().isEmpty()
                && (entity.getBehaviorId().equals(Behaviors.IDLE)
                || entity.getBehaviorId().equals(Behaviors.SEEK_WATER) || entity.getBehaviorId().equals(Behaviors.SEEK_FOOD));
    }
    void stop() { threat.response.clear(); threat.seal.abort("response_stopped"); threat.seal.reset(); threat.stage = Threat.Stage.NONE; weather.stage = Weather.Stage.NONE; }

    // ---- shared predicates -------------------------------------------------------------------------------------

    /** An ordinary free-living or kept individual: not a predator, not a house yukkuri. */
    static boolean prey(YukkuriEntity y) {
        return y.isAlive() && !y.level().isClientSide && !y.profile().dangerToYukkuri() && !y.profile().houseBody()
                && !y.inert() && !y.isPassenger();
    }
    /** Whether this individual may be steered right now (not mid-contact, birth, rescue, water). */
    public static boolean free(YukkuriEntity y) {
        return prey(y) && freeToReact(y);
    }
    static boolean freeToReact(YukkuriEntity y) {
        return !y.inert() && !y.isPassenger() && !y.isCommandPlaying() && !y.operations().active() && !y.pet().busy()
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
        public enum Stage { NONE, WATCH, RETREAT, HIDE, STRUCK }
        private final YukkuriEntity y;
        /** Shared den-mouth work for predator alarms and family outings. */
        private final DenSeal seal;
        private Stage stage = Stage.NONE;
        private LivingEntity seen;
        private LivingEntity closest;
        private double closestDistanceSq;
        private final AbortableIterationConsumer<LivingEntity> consider = this::consider;
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
        private final FlightPlanner flight;

        private final StruckResponse response;
        Threat(YukkuriEntity y) {
            this.y = y;
            seal = new DenSeal(y);
            flight = new FlightPlanner(y);
            response = new StruckResponse(y);
        }
        public boolean holdingStance() { return response.active(); }
        public boolean fightingBack() { return response.fighting(); }
        public boolean seesThreat() { return seen != null && seen.isAlive(); }
        public YukkuriAction stanceAction() { return response.action(); }
        public FlightPlanner flight() { return flight; }
        boolean airEscape(LivingEntity danger) {
            return PunishmentReaction.isHuman(danger) && y.predator().canWithdrawByAir();
        }

        public Stage stage() { return stage; }
        public boolean responding() { return stage != Stage.NONE || wants(); }
        public DenSeal seal() { return seal; }
        /** The predator (or attacker) that currently matters, or null. */
        public LivingEntity source() {
            if(y.life().isSleeping())return null;
            long now = y.level().getGameTime();
            boolean nearby = heard != null && heard.isAlive() && now < heardUntil && heard.level() == y.level() && heard.distanceToSqr(y) < 576;
            if (nearby && heard == y.getLastHurtByMob()) return heard;
            return seesThreat() ? seen : nearby ? heard : null;
        }
        public String activity() {
            if (seal.pressing()) return Behaviors.NEST_PRESS;
            return switch (stage) {
                case WATCH -> Behaviors.THREAT_WATCH;
                case RETREAT -> source() != null && flight.cornered(source()) ? Behaviors.THREAT_HIDE : Behaviors.THREAT_RETREAT;
                case HIDE -> Behaviors.THREAT_HIDE;
                case STRUCK -> Behaviors.STRUCK;
                default -> Behaviors.IDLE;
            };
        }
        String describe() {
            var s = source();
            return "threat:" + stage.name().toLowerCase(Locale.ROOT) + (s == null ? "" : " from #" + s.getId() + " d=" + Math.round(s.distanceTo(y))) + " seal=" + seal.describe();
        }

        private boolean menacing(YukkuriEntity p) {
            if (!p.isAlive() || p.isPassenger()) return false;
            var owner = y.pet().owner();
            if (owner != null && owner.equals(p.pet().owner())) return false;
            if (p.predator().obedient() && HumanCommerce.protectedAnimal(y)) return false;   // an educated predator honours a protected pet
            return p.distanceToSqr(y) < 36 || y.hasLineOfSight(p);
        }
        /** Observe a real street act, independent of the resident's appearance or attitude alone. */
        private boolean menacing(HumanNpc person) {
            return person.streetThreatTo(y);
        }
        private AbortableIterationConsumer.Continuation consider(LivingEntity candidate) {
            boolean predator = candidate instanceof YukkuriEntity;
            if (predator ? menacing((YukkuriEntity) candidate) : menacing((HumanNpc) candidate)) {
                double distanceSq = candidate.distanceToSqr(y);
                // The separate queries visited predators first. Keep that priority on an equal distance.
                if (distanceSq < closestDistanceSq
                        || distanceSq == closestDistanceSq && predator && closest instanceof HumanNpc) {
                    closest = candidate;
                    closestDistanceSq = distanceSq;
                }
            }
            return AbortableIterationConsumer.Continuation.CONTINUE;
        }
        private double alarmRange() {
            var source = source();
            return y.life().personality().threatDistance(source == null ? 0 : y.life().personality().fear(source.getUUID()));
        }

        /** Every few ticks: look around, then alarm or notice. */
        void scan() {
            if (!prey(y) || y.life().isSleeping()) { seen = null; heard = null; return; }
            var level = (ServerLevel) y.level();
            long now = level.getGameTime();
            closestDistanceSq = Double.MAX_VALUE;
            level.getEntities().get(ThreatCandidates.TYPE, y.getBoundingBox().inflate(16, 6, 16), consider);
            LivingEntity best = seen = closest;
            closest = null;
            if (best == null || holdingStance()) return;
            double d = Math.sqrt(closestDistanceSq);
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
                    if (!response.homeTactic(best)) fleeFromPredator(best);
                    warnKin(best, false);
                } else if ((d <= 4 || hunting) && !response.homeTactic(best)) fleeFromPredator(best);
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
        void warnKin(LivingEntity danger, boolean hurt) {
            long now = y.level().getGameTime();
            for (var z : y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(12, 5, 12),
                    o -> o != y && prey(o) && kin(y, o))) {
                if (!hurt && danger instanceof HumanNpc && z.status().identity().companion()) continue;
                if (PunishmentReaction.decidesAsPlantParent(z, y, danger) && PunishmentReaction.perceives(z, y, danger)) continue;
                z.status().reactions().threat().heard(danger, now);
            }
        }
        void heard(LivingEntity danger, long now) { heard(danger, now, HEARD); }
        /** A guard's visible warning or shoo. The threat goal can steer once the mouth is free to move. */
        public void warnedBy(LivingEntity guard) {
            long now = y.level().getGameTime();
            cooldownUntil = now;
            heard(guard, now);
        }
        /** The one that just hurt this body is remembered for the whole danger window: a bitten animal does not forget it. */
        private void attackedBy(LivingEntity danger) {
            long now = y.level().getGameTime();
            heard = danger;
            heardUntil = now + SAME_DANGER;
            know(danger, now);
            cooldownUntil = now;
        }

        public void struck(DamageSource source, float loss, BodyHit.Kind kind, String incident) {
            if (y.isAlive()) y.interrupt(InterruptContext.hurt(source), InterruptPhase.AFTER_DANGER_STRIKE);
            if (y.isAlive() && !y.body().dying()) y.interruptSpeech();
            var actor = source.getEntity() instanceof LivingEntity other && other != y ? other : null;
            boolean homeResponse = actor != null && (seal.holdsThroughHit() || response.leadingFrom(actor));
            seal.struck(actor);
            boolean human = PunishmentReaction.isHuman(actor);
            int before = 0;
            if (actor != null) {
                if (!human) attackedBy(actor);
                warnKin(actor, true);
                if (actor instanceof Player player) HumanNpc.witnessedStreetHurt(y, player);
                if (human) before = PunishmentReaction.recordHuman(y, actor, incident, loss);
                else if (loss > 0 && !(kind == BodyHit.Kind.BITE_WOUND && actor instanceof YukkuriEntity peer
                        && peer.sanction().target() == y)) LearnedFear.witnessed(y, actor, "hurt");
            }
            if (!y.isAlive()) return;
            boolean puff = !human && ProtectivePuff.witnessedHit(y, actor, !homeResponse);
            if (!human) y.life().personality().hurt(actor == null ? null : actor.getUUID());
            if (y.body().dying()) return;
            if (human && loss > 0) y.mind().felt(EmotionEvent.FRIGHTENED);
            SurfaceApproach.halt(y);
            if (human && y.pet().conscience().taking(actor)) {
                // The owner's blow on a caught pet is its punishment, taken like the scold: no flight, no angry puff; the penance answers it.
                response.clear();
                y.setPanicTicks(0);
                GameplayLog.event(y, "threat", "struck stance=TAKEN attacker={} loss={} cause=penance", actor.getUUID(), loss);
                return;
            }
            if (y.shellRetracts()) {
                response.clear();
                y.setPanicTicks(112);
                y.shellRetraction().tick(112);
                return;
            }
            if (!homeResponse && (puff || actor instanceof YukkuriEntity peer && (y.profile().dangerToYukkuri()
                    ? y.predator().retaliate(peer) : !peer.profile().dangerToYukkuri() && y.getVariant() != YukkuriVariant.MYON
                    && !y.newbornStage() && y.groupInteraction().defense().respond(peer)) || y.myon().standGround(source))) {
                response.clear();
                y.setPanicTicks(0);
                return;
            }
            if (actor == null) {
                response.flee(null, loss > 0);
                GameplayLog.event(y, "threat", "struck stance=FLEE cause={} loss={}", source.getMsgId(), loss);
            } else {
                var choice = response.decide(actor, loss);
                if (human) logHuman(actor, incident, before, choice);
            }
        }

        void humanIncident(LivingEntity actor, String incident) {
            int before = PunishmentReaction.recordHuman(y, actor, incident, 0);
            if (!y.isAlive() || y.body().dying()) return;
            y.interruptSpeech();
            logHuman(actor, incident, before, response.decide(actor, 0));
        }

        private void logHuman(LivingEntity actor, String incident, int before, StruckStance choice) {
            String name = switch (choice) {
                case FLEE -> "ESCAPE";
                case PUFF_FIGHT -> "DEFIANT";
                default -> choice.name();
            };
            GameplayLog.event(y, "punishment", "victim actor={} incident={} response={} fear={} -> {} health={}",
                    actor.getUUID(), incident, name, before, y.life().personality().fear(actor.getUUID()), y.getHealth());
        }
        private void heard(LivingEntity danger, long now, int window) {
            if(y.life().isSleeping())return;
            if (danger == y || !danger.isAlive()) return;
            heard = danger; heardUntil = now + window;
            if (!fresh(danger, now)) return;
            know(danger, now);
            if (now >= noticeUntil && stage == Stage.NONE) { noticeUntil = now + 400; fright(danger, 60); }
        }

        boolean wants() {
            if (holdingStance()) return freeToReact(y) && !y.isPanicking();
            var s = source();
            if (s == null || !free(y) || s instanceof HumanNpc && y.isLeashed() || y.grief().protecting()
                    || y.groupInteraction().defense().active() || y.myon().fighting() || y.isPanicking()
                    || y.family().carry().shelteredFrom(s) || y.level().getGameTime() < cooldownUntil) return false;
            return stage != Stage.NONE || s.distanceToSqr(y) <= 256 || s == heard;
        }
        boolean keeps() {
            if (stage == Stage.STRUCK || holdingStance()) return freeToReact(y) && !y.isPanicking();
            return stage != Stage.NONE && free(y) && !(source() instanceof HumanNpc && y.isLeashed()) && !y.grief().protecting()
                && !y.groupInteraction().defense().active() && !y.myon().fighting()
                && !y.isPanicking() && !y.family().carry().shelteredFrom(source()); }
        void start() {
            if (holdingStance()) { stage = Stage.STRUCK; return; }
            // Seeing the source of a warning is enough to retreat; unseen warnings retain the short listening phase.
            var danger = source();
            seal.reset();
            if (danger != null && response.homeTactic(danger)) { stage = Stage.STRUCK; return; }
            stage = danger != null && y.hasLineOfSight(danger) ? Stage.RETREAT : Stage.WATCH; ticks = 0; lostSince = 0;
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
            if (stage == Stage.STRUCK) response.clear();
            flight.forgetRoute();
            y.family().carry().endFlight();
            if (!y.isPanicking() || !seal.committedTo(y.getLastHurtByMob())) seal.reset();
            if (stage != Stage.NONE) GameplayLog.limited(y, "threat", "end", 1200,
                    "response ended stage={} phaseTicks={} cooldown={}", stage, ticks, cooldown);
            stage = Stage.NONE; ticks = 0; cooldownUntil = y.level().getGameTime() + cooldown;
            y.getNavigation().stop();
        }

        void tick() {
            if (stage == Stage.STRUCK || holdingStance()) {
                stage = Stage.STRUCK;
                if (!response.tick()) end(100);
                return;
            }
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
                    if (safeAt(s)) { stage = Stage.HIDE; ticks = 0; return; }
                    flight.flee(s);
                    if (flight.cornered(s) || d > 20 || ticks > 400 && d > 16) { stage = Stage.HIDE; ticks = 0; }
                }
                case HIDE -> {
                    if (s != null && seal.drive(s)) return;
                    if (lostSince != 0 && now - lostSince > 80) { flight.threatLost(); end(100); return; }
                    if (s != null && flight.fleeBlocked(s) && !safeAt(s)) return;
                    if (s != null && s.distanceToSqr(y) <= 256 && !safeAt(s)) {
                        if (flight.flee(s)) { stage = Stage.RETREAT; ticks = 0; }
                        return;
                    }
                    y.getNavigation().stop();
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
    }

    // ---- shared ground rules for reactions ---------------------------------------------------------------------

    /** Chosen cover stand with its depth under the roof, this body's preferred depth and crowding score. */
    record CoverChoice(BlockPos pos, int depth, double preferred, double crowd) {}

    /**
     * The one cover query: reachable covered dry stands scored by a personality/state-shifted depth interval plus physical
     * crowding from {@link Encounter#standFree}, and farther from the danger than the body itself when a danger is given.
     * No claim table or reservation. The weather goal and flight/outing paths share it.
     */
    public static BlockPos findCover(YukkuriEntity y, Vec3 danger, int offset) {
        var choice = chooseCover(y, danger, offset, pos -> true, true);
        return choice == null ? null : choice.pos();
    }

    static CoverChoice chooseCover(YukkuriEntity y, Vec3 danger, int offset, Predicate<BlockPos> allowed, boolean allowOccupied) {
        var level = (ServerLevel) y.level();
        var life = y.life();
        double preferred = preferredDepth(y, level);
        var home = life.homeStand();
        if (home != null && allowed.test(home) && life.covered(home) && Sky.at(level, home) == Sky.CLEAR
                && !Encounter.occupied(y, Vec3.atBottomCenterOf(home)) && life.reachable(home, false)
                && farther(home, y, danger))
            return new CoverChoice(home, coverDepth(level, home), preferred, 0);
        var origin = y.blockPosition();
        int radius = TravelRange.search(y, 16);
        var nearby = level.getEntitiesOfClass(YukkuriEntity.class, new AABB(origin).inflate(radius + 2, 4, radius + 2),
                o -> o != y && o.isAlive() && !y.hasPassenger(o) && y.getVehicle() != o);
        record Scored(BlockPos pos, int depth, double crowd, double score) {}
        var candidates = new ArrayList<Scored>();
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
                if (!farther(pos, y, danger) || !allowed.test(pos)) continue;
                Vec3 spot = Vec3.atBottomCenterOf(pos);
                boolean occupied = Encounter.occupied(y, spot);
                if (occupied && !allowOccupied) continue;
                if (!occupied && !Encounter.standFree(y, spot, spot)) continue;
                int depth = coverDepth(level, pos);
                double crowd = coverCrowding(y, spot, occupied, nearby);
                double diff = depth - preferred;
                double score = 0.8 * Math.sqrt(pos.distSqr(origin)) + 6.0 * diff * diff + crowd;
                candidates.add(new Scored(pos.immutable(), depth, crowd, score));
                break;
            }
        }
        candidates.sort(Comparator.comparingDouble(Scored::score));
        if (candidates.isEmpty()) return null;
        for (int tried = 0; tried < Math.min(8, candidates.size()); tried++) {
            var c = candidates.get(Math.floorMod(offset + tried, candidates.size()));
            if (life.reachable(c.pos(), false)) return new CoverChoice(c.pos(), c.depth(), preferred, c.crowd());
        }
        return null;
    }

    /** Preferred cover depth (1..4.5 cells from open sky), shifted continuously by boldness, unease, wetness, storm and baby/carrying. */
    private static double preferredDepth(YukkuriEntity y, ServerLevel level) {
        double bold = y.life().personality().boldness() / 100.0;
        double unease = Math.clamp(y.mind().unease() / 100.0, 0, 1);
        double wet = Math.clamp(y.life().wetness() / 100.0, 0, 1);
        boolean storm = level.isThundering();
        boolean youngOrCarrying = !y.family().adult() || y.family().carry().carrying();
        return Math.clamp(0.8 + 2.8 * (1.0 - bold) + 1.0 * unease + 0.8 * wet + (storm ? 1.0 : 0) + (youngOrCarrying ? 1.0 : 0), 1.0, 4.5);
    }

    /** Horizontal Chebyshev steps from {@code pos} to the nearest open-sky cell (1 = roof edge, capped at 5). */
    private static int coverDepth(ServerLevel level, BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int d = 1; d <= 4; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.abs(dx) != d && Math.abs(dz) != d) continue;
                    int nx = x + dx, nz = z + dz;
                    if (!level.hasChunkAt(new BlockPos(nx, y, nz))
                            || level.getHeight(Heightmap.Types.MOTION_BLOCKING, nx, nz) <= y) return d;
                }
            }
        }
        return 5;
    }

    /** Crowding term from live physical occupancy (no reservation): occupied cells are heavily penalised; adjacent kin attract, strangers keep a small gap. */
    private static double coverCrowding(YukkuriEntity y, Vec3 spot, boolean occupied, List<YukkuriEntity> nearby) {
        double crowd = occupied ? 60.0 : 0.0;
        for (var other : nearby) {
            double dist = spot.subtract(other.position()).horizontalDistance();
            double spacing = (y.getBbWidth() + other.getBbWidth()) * 0.5 + 0.55;
            if (dist > 0.01 && dist < spacing) {
                crowd += kin(y, other) || y.life().personality().trust(other.getUUID()) > 0 ? -2.0 : 2.0;
            }
        }
        return crowd;
    }

    /** Horizontal yaw facing the nearest rain-open edge from {@code pos}, so a settled body looks out at the rain. */
    private static float outwardRainYaw(ServerLevel level, BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int d = 1; d <= 5; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.abs(dx) != d && Math.abs(dz) != d) continue;
                    int nx = x + dx, nz = z + dz;
                    if (level.hasChunkAt(new BlockPos(nx, y, nz))
                            && level.getHeight(Heightmap.Types.MOTION_BLOCKING, nx, nz) <= y)
                        return SurfaceApproach.yaw(Vec3.atBottomCenterOf(pos), new Vec3(nx + 0.5, y, nz + 0.5));
                }
            }
        }
        return 0;
    }

    /** A flight point only counts when it is clearly farther from the danger than the body is standing now. */
    private static boolean farther(BlockPos pos, YukkuriEntity y, Vec3 danger) {
        return danger == null || Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(danger))
                > Math.sqrt(y.position().distanceToSqr(danger)) + FLIGHT_GAIN;
    }
    /** Blocks of extra distance from the danger a covered stand must offer to be worth hiding in. */
    private static final double FLIGHT_GAIN = 8;

    /** A short retreat to a supported stand, with a local search for cover and side exits when the usual flee point fails. */
    public static Vec3 escapeStand(YukkuriEntity body, Entity danger, int distance, Set<BlockPos> tried) {
        return escapeStand(body,danger.position(),danger,distance,tried);
    }

    static Vec3 escapeStand(YukkuriEntity body, Vec3 dangerAt, Entity danger, int distance, Set<BlockPos> tried) {
        return escapeStand(body, dangerAt, danger, distance, tried, at -> true);
    }

    public static Vec3 escapeStand(YukkuriEntity body, Entity danger, int distance, Predicate<Vec3> acceptable) {
        return escapeStand(body, danger.position(), danger, distance, Set.of(), acceptable);
    }

    private static Vec3 escapeStand(YukkuriEntity body, Vec3 dangerAt, Entity danger, int distance, Set<BlockPos> tried, Predicate<Vec3> acceptable) {
        var obstacle = danger==null?null:danger.getBoundingBox();
        double currentDistance = body.position().distanceTo(dangerAt);
        var away = body.status().reactions().threat().flight().destination(dangerAt, distance);
        if (away != null) {
            var stand = SurfaceApproach.floor(body, away);
            if (stand != null && body.distanceToSqr(stand) >= 1 && !tried.contains(BlockPos.containing(stand))
                    && stand.distanceTo(dangerAt) > currentDistance && acceptable.test(stand)
                    && SurfaceApproach.canRetreatTo(body, stand, obstacle)) return stand;
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
                    || !TravelRange.babyDestination(body, BlockPos.containing(stand)) || !acceptable.test(stand)) continue;
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
                if (SurfaceApproach.canRetreatTo(body, stand, obstacle)) return stand;
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
        /** Brief settle under cover looking out at the rain before releasing to routine dry-area life or huddling. */
        static final int SETTLE_TICKS = 30;
        static final int SHAKE_TICKS = EcologyPresentation.duration(Behaviors.RAIN_SHAKE),
                STARTLE_TICKS = EcologyPresentation.duration(Behaviors.THUNDER_STARTLE);
        private final YukkuriEntity y;
        private Stage stage = Stage.NONE, planned = Stage.NONE;
        private Sky sky = Sky.CLEAR;
        private BlockPos cover;
        private CoverChoice chosenCover;
        private float outwardYaw;
        /** Shelter approach retry gate: a failed cell or search is not retried until its world signature changes. */
        private Attempts shelterAttempts = new Attempts();
        private String pendingBeat, pendingDeclaredFace, pendingLine;
        /** The queued beat found an encounter open and waits for it to end (the encounter's face is cleared then, so the beat shows as declared). */
        private boolean beatWaits;
        private long searchAfter, shakeAfter, huddleAfter, partnerScanAfter, startled = Long.MIN_VALUE / 2;
        private int ticks;
        private boolean spoke;
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
        /** Still on its way to cover (running or searching), not yet standing in one. */
        boolean running() { return stage == Stage.RUN || stage == Stage.SEARCH; }
        public Sky sky() { return sky; }
        /** Behavior id of the running response (PresentationGoals.WEATHER, EcologyPresentation). */
        public String activity() {
            if (startledNow() && (stage == Stage.RUN || stage == Stage.SEARCH || stage == Stage.HUDDLE)) return Behaviors.THUNDER_STARTLE;
            return switch (stage) {
                case RUN, SEARCH, FLOOD -> Behaviors.RAIN_RUN;
                case WAIT -> shaken() ? Behaviors.RAIN_WAIT : Behaviors.IDLE;
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

        /** Observes the actual face published by {@code PresentationArbiter} and logs the queued beat with declared vs shown. */
        public void observeFace(String shown) {
            if (pendingBeat == null) return;
            // While an encounter is open it owns the face: the beat waits and is shown (and logged) once the encounter has ended.
            if (y.getNavigation() instanceof YukkuriGroundNavigation g && g.encountering()) { beatWaits = true; return; }
            GameplayLog.event(y, "weather", "beat={} declared={} shown={} line={} depth={} preferred={} cover={}",
                    pendingBeat, pendingDeclaredFace, shown, pendingLine,
                    chosenCover == null ? 0 : chosenCover.depth(),
                    chosenCover == null ? "0.00" : String.format(Locale.ROOT, "%.2f", chosenCover.preferred()),
                    cover == null ? "none" : cover.toShortString());
            pendingBeat = null;
        }

        private void queueBeat(String beat, String declaredFace, String line) {
            pendingBeat = beat;
            beatWaits = false;
            pendingDeclaredFace = declaredFace;
            pendingLine = line;
        }

        void scan() {
            sky = prey(y) ? Sky.at(y.level(), y.blockPosition()) : Sky.CLEAR;
            if (sky == Sky.CLEAR && stage == Stage.NONE && !y.level().isRaining()) {
                spoke = false;
                shelterAttempts = new Attempts();
            }
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
            return life.nutrition() < DRY_NUTRITION || life.toiletPressure() >= 90 || life.energy() < 10;
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
                    || Sky.open(y.level(), y.blockPosition()) == Sky.STORM && personality.temperament().is(Gate.TIMID);
        }
        boolean wants() {
            if (stage != Stage.NONE) return true;
            long now = y.level().getGameTime();
            if (!free(y) || y.isPanicking()) return false;
            if (approachingWater(3) != null) { planned = Stage.FLOOD; return true; }
            if (y.life().leavingFoundShelter()) return false;
            if (bothered()) {
                if (pressing()) return false;
                if (now >= searchAfter) {
                    if (!SearchBudget.take((ServerLevel) y.level())) return false;
                    pickCover();
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
                GameplayLog.limited(y, "weather", "huddle.busy." + busy, 1200, "sheltered and uneasy but busy={} unease={} cold={}", () -> new Object[]{
                            busy, Math.round(y.mind().unease()), y.life().temperature().cold()});
                return false;
            }
            planned = Stage.HUDDLE;
            return true;
        }
        boolean keeps() {
            if (stage == Stage.NONE) return false;
            if (!free(y) || y.isPanicking()) return false;
            if (stage == Stage.FLOOD) return true;
            if (pressing()) return false;
            return stage == Stage.SHAKE || stage == Stage.HUDDLE || y.level().isRaining();
        }
        void start() {
            ticks = 0;
            stage = planned;
            switch (stage) {
                case SHAKE -> {
                    y.cancelReaction();
                    queueBeat("rain_shake", "chewing_closed", "none");
                    GameplayLog.limited(y, "weather", "shake.start", 600, "shaking water off wetness={} after={}", () -> new Object[]{
                            y.life().wetness(), y.waterDistress().closedWithin(200) ? "water" : y.level().isRaining() ? "rain" : "rain_stopped"});
                }
                case HUDDLE -> beginHuddle();
                default -> {
                    y.life().wakeForDanger("weather");
                    y.life().interrupt();
                    if (stage != Stage.FLOOD || approachingWater(1) == null)
                        y.family().carry().startFlight(stage == Stage.FLOOD ? "rising_water" : "weather", null);
                    GameplayLog.limited(y, "weather", "start", 1200, "shelter response started sky={} stage={} cover={}", sky, stage, cover);
                    if (chosenCover != null) logCoverChosen("chosen");
                    if (!spoke) {
                        spoke = true;
                        boolean storm = sky == Sky.STORM;
                        if (y.life().wetness() > 0 || y.life().temperature().cold()) y.mind().felt(EmotionEvent.CHILLED); // running from rain is not cold
                        String runFace = storm ? "surprise" : y.life().personality().boldness() < 40 ? "teary" : "neutral";
                        String runLine = stage == Stage.FLOOD ? "afraid" : "rain_run";
                        y.showReaction(runFace, runLine, 80);
                        queueBeat("rain_run", runFace, runLine);
                    }
                }
            }
        }
        void end() {
            y.family().carry().endFlight();
            if (stage == Stage.SHAKE && ticks < SHAKE_TICKS) shakeAfter = y.level().getGameTime() + SHAKE_TICKS; // cut off: it shakes again soon
            if (stage == Stage.HUDDLE) {
                GameplayLog.limited(y, "weather", "huddle.end", 600, "huddle ended partner={} pressed={} ticks={} unease={} cold={}", () -> new Object[]{
                            partner == null ? null : partner.getUUID(), pressedAt >= 0, ticks, Math.round(y.mind().unease()), y.life().temperature().cold()});
                huddleAfter = y.level().getGameTime() + HUDDLE_REST;
                partner = null; pressedAt = -1;
            } else if (stage != Stage.NONE) GameplayLog.limited(y, "weather", "end", 1200,
                    "shelter response ended stage={} phaseTicks={} cover={} at={}", stage, ticks, cover, y.blockPosition().toShortString());
            if (!y.level().isRaining() || Sky.at(y.level(), y.blockPosition()) == Sky.CLEAR) shelterAttempts = new Attempts();
            stage = Stage.NONE; ticks = 0; cover = null; chosenCover = null; pendingBeat = null; beatWaits = false;
            y.getNavigation().stop();
        }

        private void searchOnFoot() {
            stage = Stage.SEARCH; ticks = 0; cover = null; chosenCover = null;
            y.getNavigation().stop();
        }

        private static String coverKey(BlockPos pos) { return "cover:" + pos.getX() + "," + pos.getY() + "," + pos.getZ(); }

        private WorldSignature shelterSignature(BlockPos target) {
            Vec3 targetVec = target == null ? null : Vec3.atBottomCenterOf(target);
            return WorldSignature.of(targetVec, y.position(), Encounter.occupantIds(y, targetVec), y.level().getGameTime());
        }

        private void recordShelterFailure(BlockPos cell, Reason reason) {
            if (cell == null) return;
            String key = coverKey(cell);
            var sig = shelterSignature(cell);
            if (!shelterAttempts.excluded(key, sig)) shelterAttempts.fail(key, reason, 0, sig);
        }

        private void pickCover() { pickCover(true); }

        private void pickCover(boolean allowOccupied) {
            var parent = ParentCarry.flightEscort(y, null);
            if (parent != null) {
                var shelter = parent.status().reactions().weather.cover;
                if (shelter != null && !Encounter.occupied(y, Vec3.atBottomCenterOf(shelter)) && y.life().reachable(shelter, false)) {
                    var level = (ServerLevel) y.level();
                    chosenCover = new CoverChoice(shelter, coverDepth(level, shelter), preferredDepth(y, level), 0);
                    cover = shelter;
                    return;
                }
            }
            if (shelterAttempts.exhausted()) { chosenCover = null; cover = null; return; }
            chosenCover = Reactions.chooseCover(y, null, 0, pos -> !shelterAttempts.excluded(coverKey(pos), shelterSignature(pos)), allowOccupied);
            cover = chosenCover == null ? null : chosenCover.pos();
        }

        private boolean retargetCover(ServerLevel level, Reason reason) {
            return retargetCover(level, reason, Double.POSITIVE_INFINITY);
        }

        private boolean retargetCover(ServerLevel level, Reason reason, double maxDistFromCurrent) {
            BlockPos previous = cover;
            recordShelterFailure(previous, reason);
            if (shelterAttempts.exhausted()) return false;
            double limitSqr = Double.isFinite(maxDistFromCurrent) ? maxDistFromCurrent * maxDistFromCurrent : Double.POSITIVE_INFINITY;
            var next = Reactions.chooseCover(y, null, 0,
                    pos -> !shelterAttempts.excluded(coverKey(pos), shelterSignature(pos))
                            && (previous == null || !Double.isFinite(limitSqr) || pos.distSqr(previous) <= limitSqr),
                    false);
            if (next != null && !next.pos().equals(previous)) {
                chosenCover = next;
                cover = next.pos();
                logRetarget(previous, reason);
                return true;
            }
            return false;
        }

        private void logRetarget(BlockPos previous, Reason reason) {
            GameplayLog.event(y, "weather", "cover retargeted from={} pos={} reason={} depth={} preferred={} crowd={} boldness={} unease={} wetness={}",
                    previous == null ? "none" : previous.toShortString(), chosenCover.pos().toShortString(), reason,
                    chosenCover.depth(), String.format(Locale.ROOT, "%.2f", chosenCover.preferred()),
                    String.format(Locale.ROOT, "%.2f", chosenCover.crowd()),
                    y.life().personality().boldness(), Math.round(y.mind().unease()), Math.round(y.life().wetness()));
        }

        /**
         * Asks the settled body standing in {@code cell} (its own cell, the one this body wants or must pass through) to shift to a free
         * covered stand clear of that cell, by the shared {@link Encounter#makeRoom}: it turns, then walks; this body then takes the cell.
         * False when there is no such body or stand (a body still walking in is not asked), and this body looks for another cover.
         */
        private boolean askRoom(ServerLevel level, YukkuriEntity occupant, BlockPos cell) {
            if (occupant == null || occupant.status().reactions().weather.running()) return false;
            if (!Encounter.makeRoom(occupant, y, Vec3.atBottomCenterOf(cell), occupant.position().subtract(y.position()),
                    at -> Sky.at(level, BlockPos.containing(at)) == Sky.CLEAR && y.life().covered(BlockPos.containing(at)))) return false;
            if (!cell.equals(cover)) {
                var previous = cover;
                cover = cell;
                chosenCover = new CoverChoice(cell, coverDepth(level, cell), preferredDepth(y, level), 0);
                logRetarget(previous, Reason.NO_ROOM);
            }
            SurfaceApproach.halt(y);
            return true;
        }

        private void logCoverChosen(String action) {
            if (chosenCover == null) return;
            GameplayLog.event(y, "weather", "cover {} pos={} depth={} preferred={} crowd={} boldness={} unease={} wetness={}",
                    action, chosenCover.pos().toShortString(), chosenCover.depth(),
                    String.format(Locale.ROOT, "%.2f", chosenCover.preferred()),
                    String.format(Locale.ROOT, "%.2f", chosenCover.crowd()),
                    y.life().personality().boldness(), Math.round(y.mind().unease()), Math.round(y.life().wetness()));
        }

        private void arriveCover(ServerLevel level) {
            SurfaceApproach.halt(y);
            y.family().carry().reachedCover();
            logStreetCover(level);
            y.cancelReaction();
            outwardYaw = outwardRainYaw(level, y.blockPosition());
            long now = level.getGameTime();
            if (y.life().wetness() >= SHAKE_WETNESS && now >= shakeAfter) {
                stage = Stage.SHAKE; ticks = 0;
                queueBeat("rain_shake", "chewing_closed", "none");
                GameplayLog.limited(y, "weather", "shake.arrival", 1200,
                        "shaking the rain off on arrival; wetness={} cover={}", y.life().wetness(), cover);
            } else {
                stage = Stage.WAIT; ticks = 0;
                queueBeat("rain_settle", settleFace(), "none");
            }
        }

        private String settleFace() {
            return y.species().catchingBreath() ? "teary_pant" : shaken() ? "teary" : "neutral";
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
            // A make-room exchange at the cover (asking, or being asked) is walked by the encounter alone; any other encounter on the way is
            // the encounter's own business and the rain run goes on walking.
            if (y.getNavigation() instanceof YukkuriGroundNavigation g) {
                if (g.makingRoom()) { SurfaceApproach.halt(y); return; }
                if (beatWaits && !g.encountering()) { beatWaits = false; y.cancelReaction(); }
            }
            // A thunder clap: it flinches where it is, on the run or pressed under a roof.
            if (startledNow() && stage != Stage.SHAKE) { SurfaceApproach.halt(y); return; }
            // In SEARCH (no chosen target yet), reaching a roof triggers choosing a proper interior cover cell.
            if (stage == Stage.SEARCH && raining && Sky.at(level, y.blockPosition()) == Sky.CLEAR
                    && life.covered(y.blockPosition()) && FoundShelter.streetCoverFree(y, y.blockPosition()) && ticks > 20) {
                pickCover();
                if (cover != null) { stage = Stage.RUN; ticks = 0; logCoverChosen("chosen"); return; }
                arriveCover(level);
                return;
            }
            switch (stage) {
                case FLOOD -> {
                    var water = approachingWater(3);
                    if (water == null) {
                        y.family().carry().reachedCover();
                        end();
                        return;
                    }
                    if (approachingWater(1) != null) y.family().carry().endFlight();
                    else if (y.family().carry().tickFlight(null)) return;
                    if (ticks % 10 == 1 || y.getNavigation().isDone()) {
                        var away = y.status().reactions().threat().flight().destination(Vec3.atCenterOf(water), 8);
                        if (away != null) life.retreatTo(BlockPos.containing(away), 1.4);
                    }
                }
                case RUN -> {
                    if (cover == null || !life.dryStand(cover) || !FoundShelter.streetCoverFree(y, cover)) {
                        if (!retargetCover(level, Reason.NO_ROOM)) searchOnFoot();
                        return;
                    }
                    Vec3 spot = Vec3.atBottomCenterOf(cover);
                    double reach = y.distanceToSqr(spot);
                    if (Encounter.occupied(y, spot)) {
                        if (retargetCover(level, Reason.NO_ROOM, 6.0)) return;
                        if (reach < 9 && askRoom(level, Encounter.standingOccupant(y, spot), cover)) return;
                        if (retargetCover(level, Reason.NO_ROOM)) return;
                        if (Sky.at(level, y.blockPosition()) == Sky.CLEAR && life.covered(y.blockPosition())
                                && !Encounter.occupied(y, y.position()) && FoundShelter.streetCoverFree(y, y.blockPosition())) {
                            arriveCover(level); return;
                        }
                        // No free cell and occupant could not make room: look for other cover instead of standing in the rain.
                        if (reach < 9) { searchOnFoot(); return; }
                    }
                    // Before walking into a standing body that blocks a narrow covered entrance on the way to spot, ask it to shift deeper.
                    if (reach > .25 && reach < 9) {
                        Vec3 stepDir = spot.subtract(y.position()).multiply(1, 0, 1);
                        if (stepDir.lengthSqr() > 1e-6) {
                            Vec3 dir = stepDir.normalize();
                            var ahead = Encounter.standingOccupant(y, y.position().add(dir.scale(1.1)));
                            // The body in the lane holds the cell this one would pass through: that cell becomes the target, and the body
                            // is asked to shift to the free covered stand beyond it (the old target).
                            if (ahead != null && Sky.at(level, ahead.blockPosition()) == Sky.CLEAR && life.covered(ahead.blockPosition())
                                    && ahead.getNavigation().isDone() && askRoom(level, ahead, ahead.blockPosition())) return;
                        }
                    }
                    double pace = sky == Sky.STORM ? 1.5 : 1.3;
                    var result = SurfaceApproach.walkResult(y, spot, null, pace);
                    if ((result == SurfaceApproach.Result.ARRIVED || reach < .04 && Sky.at(level, y.blockPosition()) == Sky.CLEAR)
                            && !Encounter.occupied(y, spot) && FoundShelter.streetCoverFree(y, y.blockPosition())) {
                        arriveCover(level);
                        return;
                    }
                    if (result == SurfaceApproach.Result.BLOCKED) {
                        if (retargetCover(level, Reason.NO_PATH, 6.0)) return;
                        if (reach < 9) {
                            Vec3 stepDir = spot.subtract(y.position()).multiply(1, 0, 1);
                            Vec3 ahead = stepDir.lengthSqr() > 1e-6 ? y.position().add(stepDir.normalize()) : spot;
                            var blocker = Encounter.standingOccupant(y, ahead);
                            if (blocker == null) blocker = Encounter.standingOccupant(y, spot);
                            if (blocker != null && askRoom(level, blocker, blocker.blockPosition())) return;
                        }
                        if (retargetCover(level, Reason.NO_PATH)) return;
                        if (Sky.at(level, y.blockPosition()) == Sky.CLEAR && life.covered(y.blockPosition())
                                && !Encounter.occupied(y, y.position()) && FoundShelter.streetCoverFree(y, y.blockPosition())) {
                            arriveCover(level); return;
                        }
                        searchOnFoot();
                    }
                }
                case WAIT -> {
                    SurfaceApproach.halt(y);
                    SurfaceApproach.face(y, outwardYaw);
                    if (raining && Sky.at(level, y.blockPosition()) != Sky.CLEAR && ticks > 10) {
                        if (cover == null) searchOnFoot(); else { stage = Stage.RUN; ticks = 0; }
                        return;
                    }
                    if (!raining) {
                        if (life.wetness() >= SHAKE_WETNESS && level.getGameTime() >= shakeAfter) {
                            stage = Stage.SHAKE; ticks = 0;
                            queueBeat("rain_shake", "chewing_closed", "none");
                        } else end();
                    } else if (ticks >= SETTLE_TICKS) end();
                }
                case SEARCH -> {
                    long now = level.getGameTime();
                    if (now >= searchAfter && SearchBudget.take(level)) {
                        pickCover(false);
                        searchAfter = now + 80;
                        if (cover != null) { stage = Stage.RUN; ticks = 0; logCoverChosen("chosen"); return; }
                    }
                    var sig = shelterSignature(null);
                    if ((y.getNavigation().isDone() || ticks % 80 == 0) && !shelterAttempts.excluded("search", sig)) {
                        var next = DefaultRandomPos.getPos(y, TravelRange.search(y, 14), 3);
                        boolean started = next != null && y.distanceToSqr(next) >= 4
                                && life.dryStand(BlockPos.containing(next)) && TravelRange.babyDestination(y, BlockPos.containing(next))
                                && life.navigate(BlockPos.containing(next), sky == Sky.STORM ? 1.7 : 1.4, false);
                        if (!started) shelterAttempts.fail("search", Reason.NO_PATH, 0, sig);
                    }
                    if (!raining) { stage = Stage.SHAKE; ticks = 0; queueBeat("rain_shake", "chewing_closed", "none"); }
                }
                case SHAKE -> {
                    SurfaceApproach.halt(y);
                    if (ticks < SHAKE_TICKS) return;
                    float before = life.shakeOff();
                    shakeAfter = level.getGameTime() + SHAKE_REST;
                    GameplayLog.limited(y, "weather", "shake.done", 600, "shook the water off wetness={} -> {}", before, life.wetness());
                    if (raining && Sky.at(level, y.blockPosition()) == Sky.CLEAR) {
                        outwardYaw = outwardRainYaw(level, y.blockPosition());
                        stage = Stage.WAIT; ticks = 0;
                        queueBeat("rain_settle", settleFace(), "none");
                    } else end();
                }
                case HUDDLE -> huddle(level);
                default -> { }
            }
        }

        private void logStreetCover(ServerLevel level) {
            var at = y.blockPosition();
            var roof = FoundShelter.roofKind(level, at);
            if (roof != null && FoundShelter.streetUser(y) && y.onGround() && Sky.at(level, at) == Sky.CLEAR)
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
            GameplayLog.limited(y, "weather", "hunker.start", 600, "sheltered alone pose={} unease={} cold={} boldness={} attachment={} nearest={}", () -> new Object[]{
                        Sky.open(y.level(), y.blockPosition()) == Sky.STORM ? "cower" : "tremble", Math.round(y.mind().unease()),
                        y.life().temperature().cold(), y.life().personality().boldness(), y.life().personality().attachment(), seek.nearest()});
        }
        /** It goes to press beside {@code other}, who holds still for it. */
        private void pair(YukkuriEntity other) {
            partner = other; leads = true; stand = null; facing = other.getYRot(); pressedAt = -1; approachFrom = ticks;
            var theirs = other.status().reactions().weather;
            theirs.stage = Stage.HUDDLE; theirs.ticks = 0; theirs.partner = y; theirs.leads = false; theirs.pressedAt = -1;
            GameplayLog.limited(y, "weather", "huddle.start", 600,
                    "huddle sought partner={} kin={} trust={} distance={} unease={} cold={} storm={}", () -> new Object[]{other.getUUID(), kin(y, other),
                    y.life().personality().trust(other.getUUID()), Math.round(y.distanceTo(other) * 10) / 10.0,
                    Math.round(y.mind().unease()), y.life().temperature().cold(), Sky.open(y.level(), y.blockPosition()) == Sky.STORM});
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
                    queueBeat("rain_shake", "chewing_closed", "none");
                } else end();
                return;
            }
            if (Sky.at(level, y.blockPosition()).wet() && ticks > 20) { end(); return; } // in the rain again: look for a roof
            if (partner != null && !paired()) {
                // The other left (its own need, a fright): a leader still shaken stays put alone, the one that held still is free.
                partner = null; pressedAt = -1;
                if (!leads) { end(); return; }
            }
            if (ticks > HUDDLE_LIMIT || leads && ticks > 100 && !shaken()) { end(); return; }
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
                            "huddle stand unavailable partner={} reason={} ticks={} distance={} stand={}", () -> new Object[]{partner.getUUID(),
                            stand == null ? "no_free_side" : "not_reached", ticks - approachFrom, Math.round(y.distanceTo(partner) * 10) / 10.0, stand});
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
            int suppressed = GameplayLog.admit(y, "weather", "huddle.pressed", 600);
            if (suppressed < 0) return;
            var to = partner.position().subtract(y.position());
            GameplayLog.limitedEvent(y, "weather", suppressed, "huddle pressed partner={} side={} centres={} afterTicks={}",
                    partner.getUUID(), partnerLeft ? "left" : "right", Math.round(to.horizontalDistance() * 100) / 100.0, ticks);
        }
        /** A thunder clap it heard: it flinches; a pressed pair, startled together, nestles in again after it. */
        void clap(long now, double distance) {
            if (!prey(y) || y.life().isSleeping() || y.isInWaterOrBubble()) return;
            startled = now;
            y.mind().felt(EmotionEvent.FRIGHTENED, ExcitedLeak.Stimulus.LOUD_NOISE);
            if (pressedAt >= 0) pressedAt = now + STARTLE_TICKS;
            if (stage == Stage.NONE) y.showReaction("surprise", "thunder", STARTLE_TICKS);
            GameplayLog.limited(y, "weather", "thunder", 600, "thunder clap heard distance={} stage={} partner={}", () -> new Object[]{
                        Math.round(distance), stage, partner == null ? null : partner.getUUID()});
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
        private final HookRescueGoal hookRescue;
        public ThreatGoal(YukkuriEntity y, HookRescueGoal hookRescue) {
            this.y = y;
            this.hookRescue = hookRescue;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        private Threat threat() { return y.status().reactions().threat; }
        @Override public boolean canUse() { return threat().wants() && !hookRescue.canUse(); }
        @Override public boolean canContinueToUse() {
            if (!threat().keeps()) return false;
            if (!hookRescue.canUse()) return true;
            threat().end(0);
            return false;
        }
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
        @Override public void stop() { if (weather().stage != Weather.Stage.NONE) weather().end(); }
    }
}
