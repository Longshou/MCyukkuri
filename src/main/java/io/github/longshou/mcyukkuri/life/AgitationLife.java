package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Adult-only, non-contact agitation and recovery. Ordinary relief and Alice's rare loss of control
 * are independent branches; neither starts courtship or changes fertility. Timings, probabilities,
 * distances and costs are game adaptations, not numerical rules attributed to the source works.
 */
public final class AgitationLife {
    public enum Phase { IDLE, ONSET, RELIEF, RECOVER, ALICE_WATCH, ALICE_APPROACH }
    private static final int CHECK_INTERVAL = 1200, RELIEF_COOLDOWN = 24_000, ALICE_COOLDOWN = 48_000;
    private static final int FRIGHT_COOLDOWN = 400, EPISODE_LIMIT = 420;
    private final YukkuriEntity entity;
    private Phase phase = Phase.IDLE;
    private int phaseTicks, cooldown, nextCheck = CHECK_INTERVAL, episodeTicks, frightCooldown;
    private int pathFailures, lostSightTicks, stalledTicks, closeTicks;
    private long phaseStart;
    private boolean controlling;
    private YukkuriEntity target;
    private Vec3 lastPosition;

    public AgitationLife(YukkuriEntity entity) { this.entity = entity; }
    public Phase phase() { return phase; }
    public int phaseTicks() { return phaseTicks; }
    public int cooldown() { return cooldown; }
    public boolean active() { return phase != Phase.IDLE; }
    public boolean activePresentation() { return controlling && active(); }
    /** A normal Alice, pending interrupted state or recovering Alice is not a threat. */
    public boolean agitated() { return activePresentation() && alicePhase(); }
    private boolean alicePhase() { return phase == Phase.ALICE_WATCH || phase == Phase.ALICE_APPROACH; }
    public String activity() {
        return switch (phase) {
            case ONSET -> "agitation_onset";
            case RELIEF -> "agitation_relief";
            case RECOVER -> "agitation_recover";
            case ALICE_WATCH -> "alice_watch";
            case ALICE_APPROACH -> "alice_approach";
            default -> "idle";
        };
    }
    public String expression() {
        return switch (phase) {
            case ONSET -> "confused";
            case RELIEF -> "closed_smile";
            case RECOVER -> "sleepy";
            case ALICE_WATCH, ALICE_APPROACH -> "angry";
            default -> "neutral";
        };
    }
    public YukkuriAction action() {
        return switch (phase) {
            case ONSET -> YukkuriAction.NERVOUS;
            case RECOVER -> YukkuriAction.DROOP;
            case ALICE_WATCH, ALICE_APPROACH -> YukkuriAction.LOOK_AROUND;
            default -> YukkuriAction.IDLE;
        };
    }

    /** Common maintenance never replaces hunger, growth, pregnancy, body or care ticks. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (frightCooldown > 0) frightCooldown--;
        if (!entity.isAlive() || !eligible() || entity.isNoAi()) { stop(); return; }
        if (cooldown > 0) cooldown--;
        if (nextCheck > 0) nextCheck--;
        if (active()) {
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
                && (brood == null || brood.cancelled || brood.complete())
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
        if (active()) return true; // legitimate saved/demo starting states resume when the goal gets its turn
        if (cooldown > 0 || nextCheck > 0) return false;
        nextCheck = CHECK_INTERVAL + entity.getRandom().nextInt(401);
        // Independent rare branch. Ordinary relief is never a required precursor to losing control.
        if (entity.getVariant() == YukkuriVariant.ALICE && entity.getRandom().nextInt(80) == 0) {
            begin(Phase.ALICE_WATCH, ALICE_COOLDOWN);
            return true;
        }
        if (entity.getRandom().nextInt(12) == 0) {
            begin(Phase.ONSET, RELIEF_COOLDOWN);
            return true;
        }
        return false;
    }
    private void begin(Phase next, int rest) {
        cooldown = Math.max(cooldown, rest);
        episodeTicks = 0;
        enter(next);
    }
    private void enter(Phase next) {
        phase = next;
        phaseTicks = pathFailures = lostSightTicks = stalledTicks = closeTicks = 0;
        phaseStart = entity.level().getGameTime();
        lastPosition = entity.position();
        if (next != Phase.ALICE_APPROACH) target = null;
        if (controlling) entity.getNavigation().stop();
    }
    public void stop() {
        if (active()) cooldown = Math.max(cooldown, alicePhase() ? ALICE_COOLDOWN : RELIEF_COOLDOWN);
        if (controlling) entity.getNavigation().stop();
        controlling = false;
        phase = Phase.IDLE;
        phaseTicks = episodeTicks = 0;
        target = null;
        lastPosition = null;
    }
    private void tickGoal() {
        if (!available()) { stop(); return; }
        phaseTicks++;
        // A bounded effort cost replaces perpetual bonuses. Recovery never creates free energy.
        if (phaseTicks % 20 == 0 && phase != Phase.RECOVER) entity.life().spendEnergy(alicePhase() ? 1f : .25f);
        switch (phase) {
            case ONSET -> {
                entity.getNavigation().stop();
                if (phaseTicks >= 60) enter(Phase.RELIEF);
            }
            case RELIEF -> {
                entity.getNavigation().stop();
                if (phaseTicks >= 80) enter(Phase.RECOVER);
            }
            case RECOVER -> {
                entity.getNavigation().stop();
                if (phaseTicks >= 100) stop();
            }
            case ALICE_WATCH -> {
                entity.getNavigation().stop();
                if (target == null || !validTarget(target)) target = findTarget();
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
        if (target == null || !validTarget(target) || phaseTicks >= 160) { enter(Phase.RECOVER); return; }
        if (!entity.hasLineOfSight(target)) {
            entity.getNavigation().stop();
            if (++lostSightTicks >= 20) enter(Phase.RECOVER);
            return;
        }
        lostSightTicks = 0;
        entity.getLookControl().setLookAt(target, 20, 20);
        alarmObservers();
        double spacing = (entity.getBbWidth() + target.getBbWidth()) * .5 + 1.2;
        double distance = entity.distanceToSqr(target);
        if (distance <= spacing * spacing) {
            // No contact, attack or damage. Never navigate into the other body's occupied cell.
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
                entity.getNavigation().stop();
                if (++pathFailures >= 3) enter(Phase.RECOVER);
            } else pathFailures = 0;
        }
        if (phaseTicks % 20 == 0) {
            if (lastPosition != null && entity.position().distanceToSqr(lastPosition) < .04) stalledTicks += 20;
            else stalledTicks = 0;
            lastPosition = entity.position();
            if (stalledTicks >= 60) enter(Phase.RECOVER);
        }
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
        tag.putInt("Version", 1); tag.putString("Phase", phase.name()); tag.putInt("PhaseTicks", phaseTicks);
        tag.putInt("Cooldown", cooldown); tag.putInt("NextCheck", nextCheck);
        tag.putInt("EpisodeTicks", episodeTicks); tag.putInt("FrightCooldown", frightCooldown);
        return tag;
    }
    public void load(CompoundTag tag) {
        controlling = false;
        target = null;
        lastPosition = null;
        try { phase = Phase.valueOf(tag.getString("Phase")); }
        catch (IllegalArgumentException ignored) { phase = Phase.IDLE; }
        if (!eligible()) phase = Phase.IDLE;
        phaseTicks = Math.clamp(tag.getInt("PhaseTicks"), 0, 159);
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, ALICE_COOLDOWN);
        nextCheck = tag.contains("NextCheck") ? Math.clamp(tag.getInt("NextCheck"), 0, CHECK_INTERVAL + 400) : CHECK_INTERVAL;
        episodeTicks = Math.clamp(tag.getInt("EpisodeTicks"), 0, EPISODE_LIMIT);
        frightCooldown = Math.clamp(tag.getInt("FrightCooldown"), 0, FRIGHT_COOLDOWN);
        pathFailures = lostSightTicks = stalledTicks = closeTicks = 0;
        phaseStart = entity.level().getGameTime() - phaseTicks;
        if (active()) cooldown = Math.max(cooldown, alicePhase() ? ALICE_COOLDOWN : RELIEF_COOLDOWN);
    }

    /** Lower priority than survival, handling, food, shelter and family care. */
    public static final class BehaviorGoal extends Goal {
        private final YukkuriEntity entity;
        public BehaviorGoal(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return entity.agitation().plan(); }
        @Override public boolean canContinueToUse() { return entity.agitation().active() && entity.agitation().available(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() {
            var life = entity.agitation();
            life.controlling = true;
            life.phaseStart = entity.level().getGameTime() - life.phaseTicks;
            entity.getNavigation().stop();
        }
        @Override public void tick() { entity.agitation().tickGoal(); }
        @Override public void stop() { entity.agitation().stop(); }
    }
}
