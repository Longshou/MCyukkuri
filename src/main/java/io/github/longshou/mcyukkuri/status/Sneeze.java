package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.status.SneezeRules.Trigger;

/**
 * Single shared entry point for sneezing ({@link YukkuriAction#SNEEZE_C}, own face timeline).
 * Owned by {@link StatusLife} so every trigger (dust, cold) shares one cooldown, one busy/interrupt
 * gate and one beat logger.
 */
public final class Sneeze {
    private final YukkuriEntity entity;
    private long nextAllowedTick;
    private Trigger pendingTrigger;

    Sneeze(YukkuriEntity entity) {
        this.entity = entity;
    }

    /** Shared entry point called by every real sneeze trigger (dust, cold). Returns true if a sneeze started. */
    public static boolean consider(YukkuriEntity entity, Trigger trigger, double intensity) {
        return entity != null && entity.status().sneeze().tryTrigger(trigger, intensity);
    }

    boolean tryTrigger(Trigger trigger, double intensity) {
        if (trigger == null || intensity <= 0.0 || !canSneeze(entity)) return false;
        long now = entity.level().getGameTime();
        if (!SneezeRules.ready(now, nextAllowedTick)) return false;
        double susceptibility = susceptibility(entity);
        double chance = SneezeRules.chance(trigger, intensity, susceptibility);
        if (chance <= 0.0 || entity.getRandom().nextDouble() >= chance) return false;
        if (!entity.showReaction(YukkuriAction.SNEEZE_C, "", "")) return false;
        nextAllowedTick = now + SneezeRules.cooldownTicks(susceptibility, entity.getRandom().nextDouble());
        pendingTrigger = trigger;
        return true;
    }

    /** Logs the beat once the presentation arbiter publishes the real face on the sneeze clip. */
    public void observeFace(String shown) {
        if (pendingTrigger == null) return;
        Trigger trigger = pendingTrigger;
        pendingTrigger = null;
        if (!YukkuriAction.SNEEZE_C.id().equals(entity.getAnimationId())) return;
        GameplayLog.event(entity, "body", "beat=sneeze trigger={} face={}", trigger.id(), shown);
    }

    /** Clears transient state on world reload so unloaded time is never replayed. */
    void reset() {
        nextAllowedTick = 0;
        pendingTrigger = null;
    }

    private static double susceptibility(YukkuriEntity e) {
        var stage = e.family().stage();
        return SneezeRules.susceptibility(
                stage == FamilyLife.Stage.BABY,
                stage == FamilyLife.Stage.JUVENILE,
                BodyAbility.frail(e),
                e.body().weak(),
                e.getVariant() == YukkuriVariant.PATCHOULI,
                e.body().healthRatio());
    }

    /**
     * Never interrupts danger, eating mid-bite, sleep, another active reaction, or any bound performance.
     */
    static boolean canSneeze(YukkuriEntity e) {
        if (e.level().isClientSide || !e.isAlive() || e.inert()) return false;
        if (e.isCommandPlaying() || e.hasReaction() || e.reactionAction() == YukkuriAction.SNEEZE_C) return false;
        // Danger and physical distress
        if (e.isPanicking() || e.isOnFire() || e.isInWaterOrBubble()
                || e.waterDistress().active() || e.waterDistress().airbornePlunge()
                || e.hurtTime > 0 || e.hurtTicks() > 0 || e.body().dying()
                || e.status().reactions().threat().stage() != Reactions.Threat.Stage.NONE
                || e.shellRetraction().active() || e.predatorFlying() || e.predator().active()) return false;
        // Eating mid-bite
        if (e.life().mealTicks() > 0 || !e.life().braidMealClip().isEmpty() || e.family().careChewTicks() > 0) return false;
        // Sleep
        String behavior = e.getBehaviorId();
        if (e.life().isSleeping() || e.house().sleeping()
                || behavior.equals(Behaviors.SLEEP) || behavior.equals(Behaviors.SLEEP_ENTER)
                || behavior.equals(Behaviors.HOUSE_SLEEP)) return false;
        // Standing still only: a route or steer in hand, or any real travel (a shove, a slide), would show the walk clip under
        // the planted sneeze, so a moving body is refused here. A carried body is a passenger and is refused with the contacts below.
        if (e.getDeltaMovement().horizontalDistanceSqr() > .000001
                || e.getNavigation() instanceof YukkuriGroundNavigation ground && ground.walking()) return false;
        // Bound performances, contacts and active motor actions
        if (e.operations().active() || e.agitation().active() || e.isPassenger() || e.pairedContact()
                || e.motor().current() != null
                || e.family().laboring() || e.family().birthDuty() || e.family().awaitingLanding()
                || e.pet().busy() || e.playLife().inFacilityMovement()
                || e.social().greeting() || HomeClaim.declaring(e) || e.life().protectsStep()) return false;
        return true;
    }
}
