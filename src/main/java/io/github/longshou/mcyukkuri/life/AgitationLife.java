package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Adult breeding-period presentation and Alice's rapem sequence, restored from the 八景 ver1.12
 * implementation (start.utf8.hsp label_2616..2624): gauge-driven hunt, target alarm and flight,
 * contact, the staged sukkiri act, the three-beat completion, reviews, buffet re-targeting and
 * attacker stamina-exhaustion death. FamilyLife owns the sole normal-period clock and all
 * accepted courtship/conception rules. Timings are the 1.12 act skeleton mapped to 20 tick/second.
 */
public final class AgitationLife {
    /** ONSET and RELIEF remain readable only to migrate earlier saved presentations. */
    public enum Phase { IDLE, ONSET, RELIEF, HEAT, HEAT_RELIEVED, HEAT_UNMET, RECOVER,
        ALICE_WATCH, ALICE_APPROACH, ALICE_ACT, HELD }
    private static final int CHECK_INTERVAL = 1200, HEAT_COOLDOWN = 24_000, ALICE_COOLDOWN = 48_000;
    private static final int FRIGHT_COOLDOWN = 400, EPISODE_LIMIT = 420;
    // The 1.12/U heat script resolves in three short beats (ゆっ！→すっ！→すっきり～！); a period that
    // merely times out ends on the unmet lines instead. Speech admission blocks equal-priority
    // lines until each ends (~45 ticks), so beats space accordingly.
    private static final int RELIEF_TICKS = 120, RELIEF_FIRST = 1, RELIEF_SECOND = 46, RELIEF_THIRD = 91, UNMET_TICKS = 100;
    // 1.12 rapem skeleton: up to 60 frames declaring, a chase, then a ~220 frame act with climax
    // beats near the end and release after ~340. Mapped here to 20 tick/second project ticks.
    private static final int WATCH_TICKS = 60, APPROACH_LIMIT = 360, ACT_TICKS = 240;
    private static final int ACT_CLIMAX_FIRST = 160, ACT_CLIMAX_SECOND = 200, ACT_SETTLE = 210;
    private static final int SETTLE_CHANCE = 4, BUFFET_COOLDOWN = 600;
    private final YukkuriEntity entity;
    private Phase phase = Phase.IDLE;
    private int phaseTicks, cooldown, nextCheck = CHECK_INTERVAL, episodeTicks, frightCooldown;
    private int pathFailures, lostSightTicks, stalledTicks, threatCooldown;
    private long phaseStart;
    private boolean controlling, relieved, reliefBeatsDone, actSettled, rapistEpisode, buffet;
    private YukkuriEntity target;
    private Vec3 lastPosition;
    private String encounterDescription = "尚无素鸡立记录";

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
        return controlling && (alicePhase() || phase == Phase.ALICE_ACT || phase == Phase.HELD);
    }
    public boolean blocksCourtship() {
        return alicePhase() || phase == Phase.ALICE_ACT || phase == Phase.HELD
                || phase == Phase.RECOVER || phase == Phase.HEAT_RELIEVED || phase == Phase.HEAT_UNMET;
    }
    /** A normal Alice, pending interrupted state or recovering Alice is not a threat. */
    public boolean agitated() { return activePresentation() && (alicePhase() || phase == Phase.ALICE_ACT); }
    private boolean alicePhase() { return phase == Phase.ALICE_WATCH || phase == Phase.ALICE_APPROACH; }
    public String activity() {
        return switch (phase) {
            case HEAT -> "breeding_period";
            case HEAT_RELIEVED -> "heat_relief";
            case HEAT_UNMET -> "heat_unmet";
            case RECOVER -> "agitation_recover";
            case ALICE_WATCH -> "alice_watch";
            case ALICE_APPROACH -> "alice_approach";
            case ALICE_ACT -> "alice_act";
            case HELD -> "rapem_held";
            default -> "idle";
        };
    }
    public String expression() {
        // 1.12-derived faces: the hunter wears the composite ecchi face (目50/眉54/口132/頬28)
        // from hunt through act; the pinned victim gets its own-sheet composite (目50/眉12/口188/頬18)
        // where the sheets exist, otherwise the stock crying face.
        return switch (phase) {
            case HEAT_RELIEVED -> "satisfied";
            case RECOVER, HEAT_UNMET -> "sleepy";
            case ALICE_WATCH, ALICE_APPROACH, ALICE_ACT -> "alice_agitated";
            case HELD -> victimFace();
            default -> "neutral";
        };
    }
    private String victimFace() {
        String id = entity.subspecies().assetId();
        return id.equals("reimu") || id.equals("marisa") || id.equals("alice") ? "rapem_victim" : "cry";
    }
    public YukkuriAction action() {
        return switch (phase) {
            case RECOVER, HEAT_UNMET -> YukkuriAction.DROOP;
            // From the hunt declaration onward the peni stays out (1.12 op2 frames 24/28); the
            // presentation lean supplies the body language on top of the held anatomy pose.
            case ALICE_WATCH, ALICE_ACT -> YukkuriAction.ANATOMY_PENI_EXTEND;
            case ALICE_APPROACH -> YukkuriAction.LOOK_AROUND;
            case HELD -> YukkuriAction.TREMBLE;
            default -> YukkuriAction.IDLE;
        };
    }
    /** Contact states keep their exact clip even under collision micro-movement. */
    public boolean poseLocked() { return phase == Phase.ALICE_WATCH || phase == Phase.ALICE_ACT || phase == Phase.HELD; }
    /** Conception during a still-running period resolves it as relief; only FamilyLife may set this. */
    public void markRelieved() {
        if (phase == Phase.HEAT) relieved = true;
    }

    /** Common maintenance never replaces hunger, growth, pregnancy, body or care ticks. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (frightCooldown > 0) frightCooldown--;
        if (threatCooldown > 0) threatCooldown--;
        if (phase == Phase.HELD) { tickHeld(); return; }
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
            if (!reliefBeatsDone) {
                if (++phaseTicks == RELIEF_FIRST) entity.speak("heat_relief_1");
                else if (phaseTicks == RELIEF_SECOND) entity.speak("heat_relief_2");
                else if (phaseTicks == RELIEF_THIRD) entity.speak("heat_relief_3");
            } else ++phaseTicks;
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
        if (alicePhase() || phase == Phase.ALICE_ACT) {
            episodeTicks++;
            // Summoned and freshly loaded bodies may not have received their first grounded physics step.
            // Only that missing support gets a bounded grace period; water, handling, sleep and other danger do not.
            boolean landingGrace = !controlling && episodeTicks <= 40 && !grounded() && available(false);
            if (episodeTicks > EPISODE_LIMIT || !available() && !landingGrace) stop();
        }
    }

    /** The held victim is monitored from here even before its own goal claims the move flag. */
    private void tickHeld() {
        var attacker = target;
        if (!entity.isAlive() || attacker == null || !attacker.isAlive() || attacker.level() != entity.level()
                || attacker.agitation().phase() != Phase.ALICE_ACT) { release(false); return; }
        entity.getNavigation().stop();
    }

    /** Goal-side held presentation: pinned face-forward, bobbing with each thrust (1.12 label_2624). */
    private void tickHeldGoal() {
        var attacker = target;
        if (attacker == null || !attacker.isAlive()) { release(false); return; }
        entity.getNavigation().stop();
        // The pinned body keeps facing away from the hunter; no look-back.
        phaseTicks++;
        // 1.12 struggles every ~20 frames; the 45-tick speech floor spaces the distress lines.
        if (phaseTicks % 60 == 30) entity.speak("rapem_victim");
    }

    private boolean eligible() {
        return entity.family().adult() && !entity.getVariant().isPredator()
                && (!alicePhase() && phase != Phase.ALICE_ACT || entity.getVariant() == YukkuriVariant.ALICE);
    }
    private boolean grounded() {
        return entity.onGround() || Math.abs(entity.getDeltaMovement().y) < .09
                && entity.level().getBlockCollisions(entity, entity.getBoundingBox().move(0, -.025, 0)).iterator().hasNext();
    }
    private boolean available() { return available(true); }
    private boolean available(boolean requireGround) {
        var life = entity.life();
        var brood = entity.family().brood();
        // Mid-act only the hard safety gates apply: the 1.12 act runs to its own stamina cost.
        boolean acting = phase == Phase.ALICE_ACT;
        return eligible() && entity.isAlive() && !entity.isNoAi() && !entity.isPanicking()
                && !entity.body().dying() && entity.body().coreIntact()
                && !entity.isPassenger() && !entity.isVehicle() && !entity.isLeashed()
                && !entity.isCommandPlaying() && !entity.operations().active() && !entity.pet().busy()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && (acting || requireGround || grounded())
                && !entity.waterDistress().active() && (acting || !entity.status().reactions().active())
                && (acting || entity.getHealth() >= entity.getMaxHealth() * .8) && entity.body().wound() == 0 && entity.body().infection() == 0
                && !life.isSleeping() && !life.sedated() && (acting || !life.hasUrgentNeed())
                && (acting || life.energy() >= 30 && life.nutrition() >= 40) && life.mealTicks() == 0
                && (acting || life.activity().equals("idle")) && !life.temperature().unsafeSleep()
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
        // A held victim claims MOVE/LOOK through this same goal so ordinary errands cannot drag it away.
        if (phase == Phase.HELD) return true;
        if (!available()) return false;
        if (alicePhase() || phase == Phase.ALICE_ACT) return true; // legitimate saved Alice states resume when the goal gets its turn
        if (active() || entity.family().inBreedingPeriod()) return false;
        if (cooldown > 0 || nextCheck > 0) return false;
        nextCheck = CHECK_INTERVAL + entity.getRandom().nextInt(401);
        // The Alice gauge threshold (1.12 れいぽうモード=400) is far below every other species: she hunts.
        if (entity.getVariant() == YukkuriVariant.ALICE && entity.getRandom().nextInt(80) == 0) {
            begin(Phase.ALICE_WATCH, ALICE_COOLDOWN);
            entity.speak("rapem_hunt");
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
        enter(next);
    }
    private void enter(Phase next) {
        phase = next;
        phaseTicks = pathFailures = lostSightTicks = stalledTicks = 0;
        phaseStart = entity.level().getGameTime();
        lastPosition = entity.position();
        if (next == Phase.ALICE_WATCH || next == Phase.ALICE_APPROACH || next == Phase.ALICE_ACT) rapistEpisode = true;
        else if (next == Phase.HEAT || next == Phase.IDLE) rapistEpisode = false;
        if (next != Phase.ALICE_APPROACH && next != Phase.ALICE_ACT) target = null;
        if (next == Phase.HEAT_RELIEVED && relieved) { relieved = false; reliefBeatsDone = false; }
        if (controlling) entity.getNavigation().stop();
    }
    public void stop() {
        if (phase == Phase.ALICE_ACT) interruptAct("素鸡立被打断，未结算");
        else if (alicePhase()) giveUpHunt("追击被危险或干预中断");
        if (buffet) buffet = false; // the settled hunter re-hunts almost immediately (1.12 attack_6)
        else if (active()) cooldown = Math.max(cooldown, rapistEpisode ? ALICE_COOLDOWN : HEAT_COOLDOWN);
        if (phase == Phase.HELD) { controlling = false; target = null; phase = Phase.IDLE; phaseTicks = 0; }
        if (controlling) entity.getNavigation().stop();
        controlling = false;
        relieved = false;
        // External interruption stops the presentation/active threat, never the normal period clock.
        phase = entity.isAlive() && eligible() && entity.family().inBreedingPeriod() ? Phase.HEAT : Phase.IDLE;
        phaseTicks = episodeTicks = 0;
        target = null;
        lastPosition = null;
        if (phase == Phase.HEAT) phaseStart = entity.level().getGameTime() - phaseTicks();
    }
    private void releaseGoal() {
        if (alicePhase() || phase == Phase.ALICE_ACT) { stop(); return; }
        // A held victim keeps its forced presentation until release(); the clock-side monitor owns that exit.
        if (phase == Phase.HELD) return;
        // An Alice goal that just entered recovery must release control without cancelling recovery.
        controlling = false;
        target = null;
        lastPosition = null;
    }
    private void tickGoal() {
        if (phase == Phase.HELD) { tickHeldGoal(); return; }
        if ((!alicePhase() && phase != Phase.ALICE_ACT) || !available()) { releaseGoal(); return; }
        phaseTicks++;
        switch (phase) {
            case ALICE_WATCH -> {
                entity.getNavigation().stop();
                if (target == null || !validTarget(target)) target = findTarget();
                if (target != null) entity.getLookControl().setLookAt(target, 20, 20);
                if (phaseTicks >= WATCH_TICKS) {
                    if (target == null) enter(Phase.RECOVER);
                    else enter(Phase.ALICE_APPROACH);
                }
            }
            case ALICE_APPROACH -> approach();
            case ALICE_ACT -> act();
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
                && !other.isOnFire() && !other.agitation().agitated() && !other.agitation().held()
                && entity.distanceToSqr(other) <= 144;
    }
    public boolean held() { return phase == Phase.HELD; }
    private YukkuriEntity findTarget() {
        return entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(12), this::validTarget)
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    /** The victim notices the hunter and flees (1.12 rapo_1 alarm, rapo_2 flight). */
    public void threatenedBy(YukkuriEntity attacker) {
        if (entity.level().isClientSide || phase == Phase.HELD || threatCooldown > 0
                || !entity.isAlive() || attacker == null || !attacker.isAlive() || attacker.level() != entity.level()) return;
        boolean first = frightCooldown <= 0;
        frightCooldown = FRIGHT_COOLDOWN;
        threatCooldown = 120;
        if (first) entity.speak("rapem_alarm");
        else if (entity.getRandom().nextInt(3) == 0) entity.speak("rapem_flee");
        entity.frightenFrom(attacker); // panic and run
        // Panic here is animation-only; steer an actual escape route away from the hunter.
        Vec3 away = entity.position().subtract(attacker.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < .01) away = new Vec3(entity.getRandom().nextDouble() - .5, 0, entity.getRandom().nextDouble() - .5);
        entity.life().navigate(BlockPos.containing(entity.position().add(away.normalize().scale(8))), 1.6, false);
    }

    /** Attacker-side throttle around the victim's alarm; renews the flight while the chase lasts. */
    private void threaten(YukkuriEntity victim) {
        if (phaseTicks % 40 != 1) return;
        if (entity.distanceToSqr(victim) <= 64 && entity.hasLineOfSight(victim)) victim.agitation().threatenedBy(entity);
    }

    /** The stand point directly behind the victim, where the mount is taken from. */
    private Vec3 rearStand(YukkuriEntity victim) {
        var forward = Vec3.directionFromRotation(0, victim.getYRot());
        double spacing = (entity.getBbWidth() + victim.getBbWidth()) * .5 * .9;
        return victim.position().subtract(forward.scale(spacing));
    }

    /** Contact made: pin the victim face-forward and take it from behind (1.12 label_2623). */
    private void beginAct() {
        enter(Phase.ALICE_ACT);
        actSettled = false;
        entity.getNavigation().stop();
        // Align exactly behind the pinned body, both facing the same way.
        var rear = rearStand(target);
        if (entity.level().noCollision(entity, entity.getBoundingBox().move(rear.subtract(entity.position()))))
            entity.teleportTo(rear.x, rear.y, rear.z);
        float yaw = target.getYRot();
        entity.setYRot(yaw);
        entity.yBodyRot = yaw;
        entity.yHeadRot = yaw;
        entity.speak("rapem_act");
        target.agitation().holdBy(entity);
        encounterDescription = "已从背后压制目标，素鸡立进行中";
    }

    private void approach() {
        if (target == null) target = findTarget(); // a restored episode never restores a stale entity reference
        if (target == null || !validTarget(target) || phaseTicks >= APPROACH_LIMIT) {
            giveUpHunt("目标脱离或追击超时，未接触");
            enter(Phase.RECOVER);
            return;
        }
        entity.getLookControl().setLookAt(target, 20, 20);
        threaten(target);
        // The mount is taken from behind (1.12 pins the body and leans on its back): steer to the
        // point directly behind the fleeing target, not to the target itself.
        var rear = rearStand(target);
        double spacing = (entity.getBbWidth() + target.getBbWidth()) * .5 + .45;
        double rearDistance = entity.distanceToSqr(rear);
        if (rearDistance <= .6 * .6 || entity.distanceToSqr(target) <= spacing * spacing * .64) {
            beginAct();
            return;
        }
        if (entity.hasLineOfSight(target)) {
            lostSightTicks = 0;
            if (phaseTicks % 10 == 1 && !entity.life().navigate(BlockPos.containing(rear), 1.7, false)) {
                entity.getNavigation().stop();
                if (++pathFailures >= 3) { giveUpHunt("追击路径失败，未接触"); enter(Phase.RECOVER); }
            } else if (phaseTicks % 10 == 1) pathFailures = 0;
        } else {
            entity.getNavigation().stop();
            if (++lostSightTicks >= 60) { giveUpHunt("追击中失去视线，未接触"); enter(Phase.RECOVER); }
            return;
        }
        if (phaseTicks % 20 == 0) {
            if (lastPosition != null && entity.position().distanceToSqr(lastPosition) < .04) stalledTicks += 20;
            else stalledTicks = 0;
            lastPosition = entity.position();
            if (stalledTicks >= 80) { giveUpHunt("追击停滞，未接触"); enter(Phase.RECOVER); }
        }
    }

    private void act() {
        if (target == null || !target.isAlive() || target.level() != entity.level() || !target.agitation().held()
                || phaseTicks > ACT_TICKS + 20) {
            interruptAct("目标在动作中脱离，未结算");
            return;
        }
        entity.getNavigation().stop();
        // Stay glued behind the pinned body: same facing, no drift from collision pushes.
        float yaw = target.getYRot();
        entity.setYRot(yaw);
        entity.yBodyRot = yaw;
        entity.yHeadRot = yaw;
        var rear = rearStand(target);
        if (entity.position().distanceToSqr(rear) > .09 && entity.level().noCollision(entity, entity.getBoundingBox().move(rear.subtract(entity.position()))))
            entity.teleportTo(rear.x, rear.y, rear.z);
        // 1.12 spends 体力200 across the act; exhaustion death is checked at completion.
        if (phaseTicks % 20 == 0) entity.life().spendEnergy(1.5f);
        if (phaseTicks == 60 || phaseTicks == 120) entity.speak("rapem_moan");
        if (phaseTicks == ACT_CLIMAX_FIRST) {
            entity.speak("heat_relief_1");
            target.agitation().climaxLine();
        }
        if (phaseTicks == ACT_CLIMAX_SECOND) entity.speak("heat_relief_2");
        if (phaseTicks == ACT_SETTLE) settle();
        if (phaseTicks >= ACT_TICKS) finishAct();
    }

    /** The single 1/4 draw (1.12 子作り roll) decides whether this act leaves a stem burden. */
    private void settle() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        var data = FamilyData.get(level);
        String refusal = data.aftermathRefusal(target, entity);
        if (refusal != null) { encounterDescription = refusal; return; }
        if (entity.getRandom().nextInt(SETTLE_CHANCE) != 0) {
            encounterDescription = "本轮素鸡立未产生胎次";
            return;
        }
        actSettled = data.createAftermath(target, entity) != null;
        encounterDescription = actSettled ? "素鸡立已结算：受袭者获得茎生胎次" : "最终胎次或人口检查拒绝，未产生胎次";
    }

    private void finishAct() {
        boolean settled = actSettled;
        YukkuriEntity victim = target;
        entity.speak("heat_relief_3");
        if (victim != null) victim.agitation().release(settled);
        if (entity.getRandom().nextBoolean()) entity.speak(entity.getRandom().nextBoolean() ? "rapem_review_bad" : "rapem_review_good");
        // A settled hunter may immediately look for the next body (1.12 buffet wording).
        if (settled && entity.getRandom().nextInt(3) == 0) {
            entity.speak("rapem_buffet");
            buffet = true;
            cooldown = Math.min(cooldown, BUFFET_COOLDOWN);
        }
        relieved = false;
        reliefBeatsDone = true; // the act already played all three beats
        enter(Phase.HEAT_RELIEVED);
        // 1.12 すっき死: an exhausted body does not survive its own completion.
        if (entity.isAlive() && entity.life().energy() < 12) {
            entity.getPersistentData().putBoolean("SukkiriExhaustion", true);
            entity.hurt(entity.level().damageSources().generic(), entity.getMaxHealth() + 100);
        }
    }

    private void interruptAct(String reason) {
        encounterDescription = reason;
        YukkuriEntity victim = target;
        if (victim != null && victim.agitation().held()) victim.agitation().release(false);
        entity.speak("heat_refuse"); // 1.12 発情10: anger at whoever spoiled the sukkiri time
        enter(Phase.RECOVER);
    }

    private void giveUpHunt(String reason) { encounterDescription = reason; }

    /** Victim-side entry: pinned face-forward by the hunter (1.12 相互れいぽうキャッチ). */
    public void holdBy(YukkuriEntity attacker) {
        if (entity.level().isClientSide || attacker == null || !attacker.isAlive()) return;
        phase = Phase.HELD;
        controlling = true;
        target = attacker;
        phaseTicks = 0;
        entity.getNavigation().stop();
        entity.speak("rapem_victim");
    }

    /** Victim-side release; a body left without a settled burden says it survived (1.12 発情9). */
    public void release(boolean settled) {
        if (phase != Phase.HELD) return;
        controlling = false;
        target = null;
        phase = entity.isAlive() && eligible() && entity.family().inBreedingPeriod() ? Phase.HEAT : Phase.IDLE;
        phaseTicks = 0;
        if (!settled) {
            entity.speak("rapem_victim_safe");
            entity.pet().addStress(8);
            entity.life().spendEnergy(6);
        }
    }

    /** The victim's line at the attacker's first climax beat (1.12 発情7). */
    void climaxLine() {
        if (phase == Phase.HELD) entity.speak("rapem_victim_climax");
    }

    /** Clients receive only a presentation phase, clock and currently visible target; decisions stay here. */
    public CompoundTag state() {
        var tag = new CompoundTag();
        tag.putString("Phase", activePresentation() ? phase.name() : Phase.IDLE.name());
        tag.putLong("Start", phaseStart);
        tag.putInt("TargetId", activePresentation() && target != null && (alicePhase() || phase == Phase.ALICE_ACT || phase == Phase.HELD)
                ? target.getId() : -1);
        return tag;
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Version", 5); tag.putString("Phase", phase.name()); tag.putInt("PhaseTicks", phaseTicks());
        tag.putInt("Cooldown", cooldown); tag.putInt("NextCheck", nextCheck);
        tag.putInt("EpisodeTicks", episodeTicks); tag.putInt("FrightCooldown", frightCooldown);
        tag.putBoolean("ActSettled", actSettled); tag.putBoolean("ReliefBeatsDone", reliefBeatsDone);
        tag.putString("EncounterDescription", encounterDescription);
        return tag;
    }
    public void load(CompoundTag tag) {
        controlling = false;
        target = null;
        lastPosition = null;
        threatCooldown = 0;
        rapistEpisode = false;
        buffet = false;
        actSettled = tag.getBoolean("ActSettled");
        reliefBeatsDone = tag.getBoolean("ReliefBeatsDone");
        String savedDescription = tag.getString("EncounterDescription");
        encounterDescription = savedDescription.isEmpty() ? "尚无素鸡立记录" : savedDescription.substring(0, Math.min(160, savedDescription.length()));
        try { phase = Phase.valueOf(tag.getString("Phase")); }
        catch (IllegalArgumentException ignored) { phase = Phase.IDLE; }
        // Brief resolution states from an interrupted session resume as ordinary recovery; the beats are missed, not replayed.
        if (phase == Phase.HEAT_RELIEVED || phase == Phase.HEAT_UNMET) phase = Phase.RECOVER;
        // Neither side of an act is reconstructed from a saved pose; the visible hunt may resume.
        if (phase == Phase.ALICE_ACT || phase == Phase.HELD) phase = Phase.RECOVER;
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
        phaseTicks = Math.clamp(tag.getInt("PhaseTicks"), 0, 359);
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, ALICE_COOLDOWN);
        nextCheck = tag.contains("NextCheck") ? Math.clamp(tag.getInt("NextCheck"), 0, CHECK_INTERVAL + 400) : CHECK_INTERVAL;
        episodeTicks = Math.clamp(tag.getInt("EpisodeTicks"), 0, EPISODE_LIMIT);
        frightCooldown = Math.clamp(tag.getInt("FrightCooldown"), 0, FRIGHT_COOLDOWN);
        pathFailures = lostSightTicks = stalledTicks = 0;
        phaseStart = entity.level().getGameTime() - phaseTicks();
        if (active()) { rapistEpisode = alicePhase() || phase == Phase.ALICE_ACT; cooldown = Math.max(cooldown, rapistEpisode ? ALICE_COOLDOWN : HEAT_COOLDOWN); }
    }

    /** Lower priority than survival, handling, food, shelter and family care. */
    public static final class BehaviorGoal extends Goal {
        private final YukkuriEntity entity;
        public BehaviorGoal(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return entity.agitation().plan(); }
        @Override public boolean canContinueToUse() {
            var life = entity.agitation();
            if (life.phase() == Phase.HELD) return true;
            return (life.alicePhase() || life.phase() == Phase.ALICE_ACT) && life.available();
        }
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

    /** Claims MOVE/LOOK above family errands so a seized body cannot be walked away mid-act. */
    public static final class HeldGoal extends Goal {
        private final YukkuriEntity entity;
        public HeldGoal(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return entity.agitation().held(); }
        @Override public boolean canContinueToUse() { return entity.agitation().held(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { entity.agitation().tickHeldGoal(); }
    }
}
