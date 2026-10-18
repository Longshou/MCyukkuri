package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Adult-only breeding-period presentation and Alice's independent rare agitation.
 * FamilyLife owns the sole normal-period clock and all accepted courtship/conception rules.
 * Timings, probabilities, distances and costs are project parameters, not source-game constants.
 */
public final class AgitationLife {
    /** ONSET and RELIEF remain readable only to migrate earlier saved presentations. */
    public enum Phase { IDLE, ONSET, RELIEF, HEAT, HEAT_RELIEVED, HEAT_UNMET, RECOVER, ALICE_WATCH, ALICE_APPROACH }
    private static final int CHECK_INTERVAL = 1200, HEAT_COOLDOWN = 24_000, ALICE_COOLDOWN = 48_000;
    private static final int FRIGHT_COOLDOWN = 400, EPISODE_LIMIT = 420;
    // The 1.12/U heat script resolves in three short beats (ゆっ！→すっ！→すっきり～！); a period that
    // merely times out ends on the unmet lines instead. Both are project adaptations of native wording.
    // Speech admission blocks equal-priority lines until each ends (~45 ticks), so beats space accordingly.
    private static final int RELIEF_TICKS = 120, RELIEF_FIRST = 1, RELIEF_SECOND = 46, RELIEF_THIRD = 91, UNMET_TICKS = 100;
    // Conservative project ecology parameters, not a reconstruction of source-game probabilities.
    private static final int ENCOUNTER_TICKS = 60, ENCOUNTER_MIN_APPROACH = 40, ENCOUNTER_CHANCE = 4;
    private static final double ENCOUNTER_DISTANCE = 5, ENCOUNTER_TRAVEL = 1, OBSERVER_DISTANCE = 96;
    private final YukkuriEntity entity;
    private Phase phase = Phase.IDLE;
    private int phaseTicks, cooldown, nextCheck = CHECK_INTERVAL, episodeTicks, frightCooldown;
    private int pathFailures, lostSightTicks, stalledTicks, closeTicks;
    private long phaseStart;
    private boolean controlling;
    private YukkuriEntity target;
    private Vec3 lastPosition;
    private boolean encounterSpent = true, encounterPath, encounterAvoidance, relieved;
    private UUID encounterTarget;
    private int encounterTicks;
    private double encounterTravel;
    private float encounterHealth;
    private Vec3 encounterPosition, encounterVictimPosition;
    private String encounterDescription = "尚无离屏遭遇";

    public AgitationLife(YukkuriEntity entity) { this.entity = entity; }
    public Phase phase() { return phase; }
    public int phaseTicks() { return phase == Phase.HEAT ? FamilyLife.BREEDING_PERIOD_TICKS - entity.family().sexDrive() : phaseTicks; }
    public int cooldown() { return cooldown; }
    public String encounterDescription() { return encounterDescription; }
    public boolean active() { return phase != Phase.IDLE; }
    public boolean activePresentation() {
        // Normal periods, their resolutions and recovery never reserve MOVE/LOOK or supersede family/survival work.
        if (phase == Phase.HEAT || phase == Phase.HEAT_RELIEVED || phase == Phase.HEAT_UNMET || phase == Phase.RECOVER)
            return (phase != Phase.HEAT || entity.family().inBreedingPeriod()) && available()
                    && !entity.hasReaction() && !entity.mind().active() && !entity.social().active()
                    && !entity.settlement().active() && entity.getNavigation().isDone()
                    && entity.getDeltaMovement().horizontalDistanceSqr() < .000001;
        return controlling && alicePhase();
    }
    public boolean blocksCourtship() { return alicePhase() || phase == Phase.RECOVER || phase == Phase.HEAT_RELIEVED || phase == Phase.HEAT_UNMET; }
    /** A normal Alice, pending interrupted state or recovering Alice is not a threat. */
    public boolean agitated() { return activePresentation() && alicePhase(); }
    private boolean alicePhase() { return phase == Phase.ALICE_WATCH || phase == Phase.ALICE_APPROACH; }
    public String activity() {
        return switch (phase) {
            case HEAT -> "breeding_period";
            case HEAT_RELIEVED -> "heat_relief";
            case HEAT_UNMET -> "heat_unmet";
            case RECOVER -> "agitation_recover";
            case ALICE_WATCH -> "alice_watch";
            case ALICE_APPROACH -> "alice_approach";
            default -> "idle";
        };
    }
    public String expression() {
        return switch (phase) {
            case HEAT_RELIEVED -> "satisfied";
            case RECOVER, HEAT_UNMET -> "sleepy";
            case ALICE_WATCH, ALICE_APPROACH -> "angry";
            default -> "neutral";
        };
    }
    public YukkuriAction action() {
        return switch (phase) {
            case RECOVER, HEAT_UNMET -> YukkuriAction.DROOP;
            case ALICE_WATCH, ALICE_APPROACH -> YukkuriAction.LOOK_AROUND;
            default -> YukkuriAction.IDLE;
        };
    }
    /** Conception during a still-running period resolves it as relief; only FamilyLife may set this. */
    public void markRelieved() {
        if (phase == Phase.HEAT) relieved = true;
    }

    /** Common maintenance never replaces hunger, growth, pregnancy, body or care ticks. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (frightCooldown > 0) frightCooldown--;
        if (!entity.isAlive() || !eligible() || entity.isNoAi()) { stop(); return; }
        if (cooldown > 0) cooldown--;
        if (nextCheck > 0) nextCheck--;
        if (phase == Phase.IDLE && entity.family().inBreedingPeriod()) beginHeat();
        if (phase == Phase.HEAT) {
            // Meals, care, threats and handling hide this cue without clearing FamilyLife's timer.
            if (!entity.family().inBreedingPeriod()) {
                cooldown = Math.max(cooldown, HEAT_COOLDOWN);
                enter(relieved ? Phase.HEAT_RELIEVED : Phase.HEAT_UNMET);
                relieved = false;
            } else {
                phaseStart = entity.level().getGameTime() - phaseTicks();
                // While actually standing in the idle observation cue, the period occasionally voices itself.
                if (activePresentation() && entity.level().getGameTime() % 320 == 0 && entity.getRandom().nextInt(2) == 0)
                    entity.speak("heat_want");
            }
            return;
        }
        if (phase == Phase.HEAT_RELIEVED) {
            // Staged completion beats; speech admission is already sequenced by priority 2.
            if (++phaseTicks == RELIEF_FIRST) entity.speak("heat_relief_1");
            else if (phaseTicks == RELIEF_SECOND) entity.speak("heat_relief_2");
            else if (phaseTicks == RELIEF_THIRD) entity.speak("heat_relief_3");
            if (phaseTicks >= RELIEF_TICKS) stop();
            return;
        }
        if (phase == Phase.HEAT_UNMET) {
            if (phaseTicks == 0) entity.speak("heat_unmet");
            if (++phaseTicks >= UNMET_TICKS) stop();
            return;
        }
        if (phase == Phase.RECOVER) {
            if (++phaseTicks >= 100) stop();
            return;
        }
        if (alicePhase()) {
            episodeTicks++;
            // Summoned and freshly loaded bodies may not have received their first grounded physics step.
            // Only that missing support gets a bounded grace period; water, handling, sleep and other danger do not.
            boolean landingGrace = !controlling && episodeTicks <= 40 && !grounded() && available(false);
            if (episodeTicks > EPISODE_LIMIT || !available() && !landingGrace) stop();
        }
    }

    private boolean eligible() {
        return entity.family().adult() && !entity.getVariant().isPredator()
                && (!alicePhase() || entity.getVariant() == YukkuriVariant.ALICE);
    }
    private boolean grounded() {
        return entity.onGround() || Math.abs(entity.getDeltaMovement().y) < .09
                && entity.level().getBlockCollisions(entity, entity.getBoundingBox().move(0, -.025, 0)).iterator().hasNext();
    }
    private boolean available() { return available(true); }
    private boolean available(boolean requireGround) {
        var life = entity.life();
        var brood = entity.family().brood();
        return eligible() && entity.isAlive() && !entity.isNoAi() && !entity.isPanicking()
                && !entity.body().dying() && entity.body().coreIntact()
                && !entity.isPassenger() && !entity.isVehicle() && !entity.isLeashed()
                && !entity.isCommandPlaying() && !entity.operations().active() && !entity.pet().busy()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && (!requireGround || grounded())
                && !entity.waterDistress().active() && !entity.status().reactions().active()
                && entity.getHealth() >= entity.getMaxHealth() * .8 && entity.body().wound() == 0 && entity.body().infection() == 0
                && !life.isSleeping() && !life.sedated() && !life.hasUrgentNeed()
                && life.energy() >= 30 && life.nutrition() >= 40 && life.mealTicks() == 0
                && life.activity().equals("idle") && !life.temperature().unsafeSleep()
                && !entity.family().active() && !entity.family().awaitingLanding()
                && !entity.family().hasUrgentlyHungryChild() && !entity.family().birthDuty()
                && (brood == null || brood.cancelled || brood.complete()
                    // A fresh normal gestation does not hide the brief staged completion right after conceiving.
                    || phase == Phase.HEAT_RELIEVED && brood.incidentSource == null && brood.elapsed == 0)
                && !entity.careApproach().waiting() && !entity.childhood().active()
                && !entity.house().active() && !entity.woundComfort().active()
                && !entity.aquaticLife().active() && !entity.independence().active()
                && !entity.habitat().active() && !entity.settlement().duties().active()
                && !entity.dailyLife().active() && !entity.groupInteraction().active() && !entity.playLife().active()
                && !io.github.longshou.mcyukkuri.event.WildEvents.reserved(entity)
                && !io.github.longshou.mcyukkuri.event.HomeClaim.holds(entity);
    }

    private boolean plan() {
        if (!available()) return false;
        if (alicePhase()) return true; // legitimate saved Alice starting states resume when the goal gets its turn
        if (active() || entity.family().inBreedingPeriod()) return false;
        if (cooldown > 0 || nextCheck > 0) return false;
        nextCheck = CHECK_INTERVAL + entity.getRandom().nextInt(401);
        // Independent rare branch. A normal breeding period is never a required precursor.
        if (entity.getVariant() == YukkuriVariant.ALICE && entity.getRandom().nextInt(80) == 0) {
            begin(Phase.ALICE_WATCH, ALICE_COOLDOWN);
            return true;
        }
        if (entity.getRandom().nextInt(12) == 0
                && (FamilyConfig.NATURAL_PAIRING.get() || entity.family().partner() != null)
                && entity.family().recovery() == 0
                && entity.family().beginBreedingPeriod()) beginHeat();
        // Starting a normal period is a state change, not a movement goal.
        return false;
    }
    private void beginHeat() {
        begin(Phase.HEAT, HEAT_COOLDOWN);
        controlling = false;
        relieved = false;
        phaseStart = entity.level().getGameTime() - phaseTicks();
        entity.speak("heat_start");
    }
    private void begin(Phase next, int rest) {
        cooldown = Math.max(cooldown, rest);
        episodeTicks = 0;
        if (next == Phase.ALICE_WATCH) {
            encounterSpent = encounterPath = encounterAvoidance = false;
            encounterTarget = null;
            encounterTicks = 0;
            encounterTravel = 0;
            encounterPosition = null;
            encounterVictimPosition = null;
            encounterDescription = "尚未形成合格离屏遭遇";
        }
        enter(next);
    }
    private void enter(Phase next) {
        if (next == Phase.RECOVER) cancelEncounter("本轮接近结束，未结算后果");
        phase = next;
        phaseTicks = pathFailures = lostSightTicks = stalledTicks = closeTicks = 0;
        phaseStart = entity.level().getGameTime();
        lastPosition = entity.position();
        if (next != Phase.ALICE_APPROACH) target = null;
        else encounterPosition = entity.position();
        if (controlling) entity.getNavigation().stop();
    }
    public void stop() {
        if (alicePhase()) cancelEncounter("遭遇被危险或干预中断");
        if (active()) cooldown = Math.max(cooldown, alicePhase() ? ALICE_COOLDOWN : HEAT_COOLDOWN);
        if (controlling) entity.getNavigation().stop();
        controlling = false;
        relieved = false;
        // External interruption stops the presentation/active threat, never the normal period clock.
        phase = entity.isAlive() && eligible() && entity.family().inBreedingPeriod() ? Phase.HEAT : Phase.IDLE;
        phaseTicks = episodeTicks = 0;
        target = null;
        lastPosition = null;
        encounterPosition = null;
        if (phase == Phase.HEAT) phaseStart = entity.level().getGameTime() - phaseTicks();
    }
    private void releaseGoal() {
        if (alicePhase()) { stop(); return; }
        // An Alice goal that just entered recovery must release control without cancelling recovery.
        controlling = false;
        target = null;
        lastPosition = null;
    }
    private void tickGoal() {
        if (!alicePhase() || !available()) { releaseGoal(); return; }
        phaseTicks++;
        // A bounded effort cost replaces perpetual bonuses. Recovery never creates free energy.
        if (phaseTicks % 20 == 0) entity.life().spendEnergy(1f);
        switch (phase) {
            case ALICE_WATCH -> {
                entity.getNavigation().stop();
                if (target == null || !validTarget(target)) target = findTarget();
                inspectEncounter();
                if (target != null) entity.getLookControl().setLookAt(target, 20, 20);
                alarmObservers();
                if (phaseTicks >= 60) {
                    if (target == null) enter(Phase.RECOVER);
                    else enter(Phase.ALICE_APPROACH);
                }
            }
            case ALICE_APPROACH -> approach();
            default -> { }
        }
    }

    private boolean validTarget(YukkuriEntity other) {
        return other != entity && other.isAlive() && other.level() == entity.level() && other.family().adult()
                && !other.body().dying() && other.body().coreIntact()
                && !other.getVariant().isPredator() && !other.isNoAi() && !other.isPassenger() && !other.isVehicle()
                && !other.isCommandPlaying() && !other.operations().active() && !other.pet().busy()
                && !other.family().laboring() && !other.family().birthDuty() && !other.family().braidRescueActive()
                && !other.life().sedated() && !other.life().isSleeping() && !other.isInWaterOrBubble()
                && !other.isOnFire() && !other.agitation().agitated() && entity.distanceToSqr(other) <= 100;
    }
    private YukkuriEntity findTarget() {
        return entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(8), this::validTarget)
                .stream().filter(entity::hasLineOfSight).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    private void approach() {
        if (target == null) target = findTarget(); // a restored episode never restores a stale entity reference
        if (target == null || !validTarget(target) || phaseTicks >= 160) {
            cancelEncounter("目标已脱离或接近超时，未结算后果");
            enter(Phase.RECOVER);return;
        }
        if (!entity.hasLineOfSight(target)) {
            cancelEncounter("与目标失去视线，未结算后果");
            entity.getNavigation().stop();
            if (++lostSightTicks >= 20) enter(Phase.RECOVER);
            return;
        }
        lostSightTicks = 0;
        entity.getLookControl().setLookAt(target, 20, 20);
        alarmObservers();
        if (advanceEncounter()) return;
        double spacing = (entity.getBbWidth() + target.getBbWidth()) * .5 + 1.2;
        double distance = entity.distanceToSqr(target);
        if (distance <= spacing * spacing) {
            // Stop at the configured body spacing before entering recovery.
            entity.getNavigation().stop();
            if (++closeTicks >= 30) enter(Phase.RECOVER);
            return;
        }
        closeTicks = 0;
        if (phaseTicks % 10 == 1) {
            Vec3 away = entity.position().subtract(target.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < .0001) { enter(Phase.RECOVER); return; }
            BlockPos stand = BlockPos.containing(target.position().add(away.normalize().scale(spacing + .5)));
            double standDistance = Vec3.atBottomCenterOf(stand).distanceToSqr(target.position());
            if (standDistance < spacing * spacing || !entity.life().dryStand(stand)
                    || !entity.life().navigate(stand, 1.55, false)) {
                cancelEncounter("接近路径失败，未结算后果");
                entity.getNavigation().stop();
                if (++pathFailures >= 3) enter(Phase.RECOVER);
            } else { pathFailures = 0;encounterPath = true; }
        }
        if (phaseTicks % 20 == 0) {
            if (lastPosition != null && entity.position().distanceToSqr(lastPosition) < .04) stalledTicks += 20;
            else stalledTicks = 0;
            lastPosition = entity.position();
            if (stalledTicks >= 60) enter(Phase.RECOVER);
        }
    }

    private void cancelEncounter(String reason) {
        if (encounterSpent) return;
        encounterSpent = true;
        encounterTicks = 0;
        encounterDescription = reason;
    }

    /** Any potential receiving client cancels the candidate, even behind a wall or looking away.
     * This deliberately excludes observed exhibits; it does not guess first/third-person camera FOV.
     * The proximity check also covers player/camera arrival before the tracking list catches up. */
    private boolean encounterObserved() {
        var level = (ServerLevel) entity.level();
        var tracking = level.getChunkSource().chunkMap;
        if (!tracking.getPlayersWatching(entity).isEmpty()
                || target != null && !tracking.getPlayersWatching(target).isEmpty()) return true;
        for (var player : level.players()) {
            if (nearEncounter(player) || nearEncounter(player.getCamera())) return true;
        }
        return false;
    }
    private boolean nearEncounter(Entity observer) {
        return observer.level() == entity.level() && (observer.distanceToSqr(entity) <= OBSERVER_DISTANCE * OBSERVER_DISTANCE
                || target != null && observer.distanceToSqr(target) <= OBSERVER_DISTANCE * OBSERVER_DISTANCE);
    }
    private boolean encounterEligible() {
        return target != null && validTarget(target) && !target.isRemoved() && !target.isLeashed()
                && target.family().canReceiveAftermath() && target.family().compatibleWith(entity)
                && !target.family().active() && !target.careApproach().waiting() && !target.childhood().active()
                && !target.woundComfort().active() && !target.life().temperature().unsafeSleep()
                && target.life().mealTicks() == 0 && target.hasLineOfSight(entity)
                && entity.family().recovery() == 0;
    }
    private void inspectEncounter() {
        if (encounterSpent) return;
        if (encounterObserved()) { cancelEncounter("玩家在场或可能观察，本轮不结算后果");return; }
        if (target == null) {
            if (encounterTarget != null) cancelEncounter("原目标已离开，本轮不结算后果");
            return;
        }
        if (encounterTarget != null && !encounterTarget.equals(target.getUUID())) {
            cancelEncounter("原目标已脱离，不向新目标转移结算");return;
        }
        if (!encounterEligible()) { cancelEncounter("目标资格或照护状态不允许，本轮不结算后果");return; }
        if (encounterTarget == null) {
            encounterTarget = target.getUUID();
            encounterHealth = target.getHealth();
            encounterVictimPosition = target.position();
            String refusal = FamilyData.get((ServerLevel) entity.level()).aftermathRefusal(target, entity);
            if (refusal != null) { cancelEncounter(refusal);return; }
        }
        // A new injury, treatment, displacement or competing threat interrupts this evidence chain.
        // Low initial nutrition/health is not an immunity; subsequent intervention is a cancellation.
        if (target.getHealth() != encounterHealth
                || encounterVictimPosition != null && target.position().distanceToSqr(encounterVictimPosition) > 1
                || target.isPanicking() && target.getLastHurtByMob() != null && target.getLastHurtByMob() != entity) {
            cancelEncounter("目标受到其他干预或位置突变，离屏遭遇取消");return;
        }
        encounterVictimPosition = target.position();
        if (target.isPanicking() && target.getLastHurtByMob() == entity) encounterAvoidance = true;
    }
    /** The ordinary escape can end this encounter at any tick. */
    private boolean advanceEncounter() {
        inspectEncounter();
        if (encounterSpent) return false;
        Vec3 position = entity.position();
        if (encounterPosition != null) {
            double moved = position.subtract(encounterPosition).horizontalDistance();
            if (moved > 1) { cancelEncounter("位置突变，离屏遭遇取消");return false; }
            if (encounterPath) encounterTravel += moved;
        }
        encounterPosition = position;
        if (entity.distanceToSqr(target) > ENCOUNTER_DISTANCE * ENCOUNTER_DISTANCE) {
            if (encounterTicks > 0) cancelEncounter("目标已逃出遭遇范围，未结算后果");
            return false;
        }
        if (!encounterPath || encounterTravel < ENCOUNTER_TRAVEL || !encounterAvoidance) return false;
        encounterDescription = "持续离屏遭遇，尚未结算";
        if (++encounterTicks < ENCOUNTER_TICKS || phaseTicks < ENCOUNTER_MIN_APPROACH) return false;

        // Spend the only attempt before any mutation or random draw. Refusal and reload never retry it.
        encounterSpent = true;
        boolean settled = false;
        if (encounterObserved() || !available() || !encounterEligible()
                || !entity.hasLineOfSight(target) || !entity.life().reachable(target.blockPosition(), false)) {
            encounterDescription = "最终观察或安全检查未通过，未结算后果";
        } else {
            var data = FamilyData.get((ServerLevel) entity.level());
            String refusal = data.aftermathRefusal(target, entity);
            if (refusal != null) encounterDescription = refusal;
            else if (entity.getRandom().nextInt(ENCOUNTER_CHANCE) != 0) encounterDescription = "本轮离屏遭遇未产生后果";
            else {
                settled = data.createAftermath(target, entity) != null;
                encounterDescription = settled ? "离屏遭遇已结算：茎生负担，需要照护" : "最终胎次或人口检查拒绝，未结算后果";
            }
        }
        // Only a settled assault shows the staged satisfied completion; every other outcome still recovers.
        if (settled) enter(Phase.HEAT_RELIEVED);
        else enter(Phase.RECOVER);
        return true;
    }

    private void alarmObservers() {
        if (!agitated() || phaseTicks % 20 != 1) return;
        for (var observer : entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(5), this::validTarget)) {
            if (observer.distanceToSqr(entity) <= 25 && observer.hasLineOfSight(entity)) observer.agitation().notice(entity);
        }
    }
    private void notice(YukkuriEntity threat) {
        if (frightCooldown > 0 || entity.isPanicking() || !threat.agitation().agitated()
                || !entity.family().adult() || entity.family().active() || entity.careApproach().waiting()
                || entity.childhood().active() || entity.woundComfort().active()) return;
        frightCooldown = FRIGHT_COOLDOWN; // shared across all agitated sources and persisted through reloads
        entity.pet().addStress(2);
        entity.frightenFrom(threat);
    }

    /** Clients receive only a presentation phase, clock and currently visible target; decisions stay here. */
    public CompoundTag state() {
        var tag = new CompoundTag();
        tag.putString("Phase", activePresentation() ? phase.name() : Phase.IDLE.name());
        tag.putLong("Start", phaseStart);
        tag.putInt("TargetId", activePresentation() && target != null ? target.getId() : -1);
        return tag;
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Version", 4); tag.putString("Phase", phase.name()); tag.putInt("PhaseTicks", phaseTicks());
        tag.putInt("Cooldown", cooldown); tag.putInt("NextCheck", nextCheck);
        tag.putInt("EpisodeTicks", episodeTicks); tag.putInt("FrightCooldown", frightCooldown);
        tag.putBoolean("EncounterSpent", encounterSpent);tag.putString("EncounterDescription", encounterDescription);
        return tag;
    }
    public void load(CompoundTag tag) {
        controlling = false;
        target = null;
        lastPosition = null;
        // A saved active approach may resume its visible warning, never an unobserved pending event.
        // No target, path, avoidance or exposure evidence is reconstructed from a saved pose.
        encounterSpent = true;
        encounterTarget = null;
        encounterPosition = null;
        encounterVictimPosition = null;
        encounterPath = encounterAvoidance = false;
        encounterTicks = 0;
        encounterTravel = 0;
        String savedDescription = tag.getString("EncounterDescription");
        encounterDescription = savedDescription.isEmpty() ? "尚无离屏遭遇" : savedDescription.substring(0, Math.min(160, savedDescription.length()));
        try { phase = Phase.valueOf(tag.getString("Phase")); }
        catch (IllegalArgumentException ignored) { phase = Phase.IDLE; }
        // Brief resolution states from an interrupted session resume as ordinary recovery; the beats are missed, not replayed.
        if (phase == Phase.HEAT_RELIEVED || phase == Phase.HEAT_UNMET) phase = Phase.RECOVER;
        if (phase == Phase.ONSET || phase == Phase.RELIEF) {
            // Earlier versions saved an isolated short animation. Migrate it once into the sole
            // family timer, preserving any already-saved remaining time rather than restarting it.
            var brood = entity.family().brood();
            if (eligible() && entity.family().sexDrive() == 0 && entity.family().recovery() == 0
                    && (brood == null || brood.cancelled || brood.complete())) entity.family().beginBreedingPeriod();
            phase = entity.family().inBreedingPeriod() ? Phase.HEAT : Phase.IDLE;
        }
        if (phase == Phase.HEAT && !entity.family().inBreedingPeriod()) phase = Phase.IDLE;
        if (phase == Phase.IDLE && entity.family().inBreedingPeriod()) phase = Phase.HEAT;
        if (!eligible()) phase = Phase.IDLE;
        if (alicePhase() && !tag.getBoolean("EncounterSpent")) encounterDescription = "重载已取消未完成的离屏遭遇";
        phaseTicks = Math.clamp(tag.getInt("PhaseTicks"), 0, 159);
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, ALICE_COOLDOWN);
        nextCheck = tag.contains("NextCheck") ? Math.clamp(tag.getInt("NextCheck"), 0, CHECK_INTERVAL + 400) : CHECK_INTERVAL;
        episodeTicks = Math.clamp(tag.getInt("EpisodeTicks"), 0, EPISODE_LIMIT);
        frightCooldown = Math.clamp(tag.getInt("FrightCooldown"), 0, FRIGHT_COOLDOWN);
        pathFailures = lostSightTicks = stalledTicks = closeTicks = 0;
        phaseStart = entity.level().getGameTime() - phaseTicks();
        if (active()) cooldown = Math.max(cooldown, alicePhase() ? ALICE_COOLDOWN : HEAT_COOLDOWN);
    }

    /** Lower priority than survival, handling, food, shelter and family care. */
    public static final class BehaviorGoal extends Goal {
        private final YukkuriEntity entity;
        public BehaviorGoal(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return entity.agitation().plan(); }
        @Override public boolean canContinueToUse() { return entity.agitation().alicePhase() && entity.agitation().available(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() {
            var life = entity.agitation();
            life.controlling = true;
            life.phaseStart = entity.level().getGameTime() - life.phaseTicks;
            entity.getNavigation().stop();
        }
        @Override public void tick() { entity.agitation().tickGoal(); }
        @Override public void stop() { entity.agitation().releaseGoal(); }
    }
}
