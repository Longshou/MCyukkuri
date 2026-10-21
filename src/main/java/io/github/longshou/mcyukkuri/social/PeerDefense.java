package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;

/** Transient self-defence owned by the group component; it never reserves the attacker's movement. */
public final class PeerDefense extends Goal {
    /** Project design: at or below forty percent health, survival always wins over courage. */
    public static final float FLEE_HEALTH = .4f;
    private final YukkuriEntity entity;
    private YukkuriEntity attacker;
    private long until;
    private int strikeWait;
    private String decision = "none";
    private double score;

    PeerDefense(YukkuriEntity entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public boolean active() { return attacker != null; }
    public String detail() {
        return "defense=" + (attacker == null ? "none" : attacker.getUUID()) + " decision=" + decision + " score=" + Math.round(score);
    }

    /** Called only after a real hit, push, theft or drive-off attempt. No random draw or unconditional counter-hit. */
    public boolean respond(YukkuriEntity aggressor) {
        if (aggressor == null || aggressor == entity || !aggressor.isAlive() || aggressor.level() != entity.level()) return false;
        var personality = entity.life().personality();
        double ownPower = power(entity), otherPower = power(aggressor);
        score = personality.boldness() - personality.fear(aggressor.getUUID()) * .5
                + 35 * Math.clamp(ownPower / Math.max(.01, otherPower) - 1, -1, 1);
        String refusal = refusal();
        boolean defend = refusal == null && score >= 50;
        decision = defend ? "counterattack" : refusal == null ? "outmatched_or_afraid" : refusal;
        if (defend) {
            boolean fresh = attacker != aggressor;
            attacker = aggressor;
            until = entity.level().getGameTime() + 240;
            if (fresh) strikeWait = 12;
            entity.groupInteraction().stop();
            entity.sanction().stop();
            entity.social().stop();
            entity.dailyLife().stop();
            entity.playLife().stop();
            entity.life().interrupt();
            entity.life().personality().decided("defend_self");
        } else stop();
        GameplayLog.limited(entity, "social", "defense_" + decision, 40,
                "self defense decision={} attacker={} health={}/{} energy={} boldness={} fear={} ownPower={} attackerPower={} score={}",
                decision, aggressor.getUUID(), entity.getHealth(), entity.getMaxHealth(), entity.life().energy(),
                personality.boldness(), personality.fear(aggressor.getUUID()), ownPower, otherPower, score);
        return defend;
    }

    private static double power(YukkuriEntity peer) {
        return peer.getBbWidth() * peer.species().traits().might() * peer.body().healthRatio()
                * Math.max(.15, peer.life().energy() / 100.0);
    }

    private String refusal() {
        if (entity.body().healthRatio() <= FLEE_HEALTH) return "low_health";
        if (!entity.isAlive() || entity.body().dying()) return "dying";
        if (entity.body().weak() || !entity.body().coreIntact()) return "body_unfit";
        if (entity.life().energy() < 15 || entity.life().nutrition() < 15) return "exhausted";
        if (entity.isOnFire() || entity.isInWaterOrBubble()) return "environment_danger";
        if (entity.isNoAi() || entity.isPassenger() || entity.operations().active() || entity.isCommandPlaying()) return "movement_unavailable";
        return null;
    }

    /** Health can fall from wounds between hits, so the emergency handoff also runs from the component clock. */
    void clock() {
        if (strikeWait > 0) strikeWait--;
        if (attacker == null) return;
        String reason = refusal();
        if (reason != null) retreat(reason);
        else if (!valid()) stop();
    }

    private void retreat(String reason) {
        var threat = attacker;
        decision = reason;
        stop();
        entity.fleeFrom(threat);
        GameplayLog.event(entity, "social", "self defense retreat attacker={} reason={} health={}/{} energy={}",
                threat.getUUID(), reason, entity.getHealth(), entity.getMaxHealth(), entity.life().energy());
    }

    private boolean valid() {
        return attacker != null && attacker.isAlive() && !attacker.isRemoved() && attacker.level() == entity.level()
                && !entity.isPanicking() && entity.distanceToSqr(attacker) <= 144 && entity.level().getGameTime() < until;
    }

    @Override public boolean canUse() {
        if (attacker == null) return false;
        String reason = refusal();
        if (reason != null) { retreat(reason); return false; }
        return valid();
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if (!canUse()) return;
        var target = attacker;
        entity.getLookControl().setLookAt(target, 30, 30);
        double reach = (entity.getBbWidth() + target.getBbWidth()) * .5 + .18;
        if (entity.distanceToSqr(target) > reach * reach || !entity.hasLineOfSight(target)) {
            if (entity.tickCount % 10 == 0) {
                boolean path = entity.life().navigate(target.blockPosition(), 1.2, false);
                if (!path) GameplayLog.limited(entity, "social", "defense_path", 100,
                        "self defense approach blocked attacker={} distanceSqr={}", target.getUUID(), entity.distanceToSqr(target));
            }
            return;
        }
        entity.getNavigation().stop();
        if (strikeWait > 0) return;
        strikeWait = 30;
        if (!entity.life().spendEnergy(2)) return;
        float before = target.getHealth();
        boolean accepted = target.hurt(entity.damageSources().mobAttack(entity), 1);
        GameplayLog.event(entity, "social", "self defense strike attacker={} accepted={} health={} -> {} energy={}",
                target.getUUID(), accepted, before, target.getHealth(), entity.life().energy());
        if (accepted) {
            target.knockback(.25, entity.getX() - target.getX(), entity.getZ() - target.getZ());
            target.hurtMarked = true;
        }
    }

    @Override public void stop() {
        if (attacker == null) return;
        GameplayLog.event(entity, "social", "self defense ended attacker={} decision={} health={} energy={}",
                attacker.getUUID(), decision, entity.getHealth(), entity.life().energy());
        attacker = null;
        entity.getNavigation().stop();
    }
}
