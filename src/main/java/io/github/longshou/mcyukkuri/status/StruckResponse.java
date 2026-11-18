package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.RamPerson;
import net.minecraft.world.entity.LivingEntity;

/** The victim's response window. ThreatGoal steers a held stance and FleeGoal owns flight. */
final class StruckResponse implements MotorOwner {
    private final YukkuriEntity body;
    private LivingEntity actor;
    private StruckStance stance;
    private long start, until;
    private double initialDistance;
    private RamPerson bump;

    StruckResponse(YukkuriEntity body) { this.body = body; }
    @Override public String ownerId() { return Behaviors.STRUCK; }
    @Override public boolean active() {
        return stance != null && body.level().getGameTime() < until && actor.isAlive() && actor.level() == body.level();
    }

    private boolean exitOpen(LivingEntity danger) {
        return Reactions.freeToReact(body) && !body.isLeashed() && !body.grief().protecting()
                && !body.shellRetraction().available() && body.status().reactions().threat().flight().hasExit(danger);
    }

    StruckStance decide(LivingEntity danger, float loss) {
        var p = body.life().personality();
        var id = body.status().identity();
        int young = body.level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(6), body.family()::caresFor).size();
        var inputs = new StruckStance.Inputs(p.boldness(), p.selfControl(), p.intelligence(), p.friendliness(), p.attachment(),
                body.mind().unease(), p.fear(danger.getUUID()), body.body().healthRatio(), loss, body.getMaxHealth(),
                id, body.newbornStage(), young, exitOpen(danger), danger.getUUID().equals(body.pet().owner())
                && p.trust(danger.getUUID()) >= 0, !body.body().weak() && body.life().energy() >= 15,
                stance == StruckStance.FREEZE && active());
        var choice = StruckStance.decide(inputs);
        clear();
        body.setPanicTicks(0);
        SurfaceApproach.halt(body);
        if (choice == StruckStance.FLEE) {
            if (loss == 0) body.fleeFrom(danger);
            flee(danger);
        }
        else {
            actor = danger;
            stance = choice;
            start = body.level().getGameTime();
            initialDistance = body.distanceToSqr(actor);
            if (choice == StruckStance.PUFF_FIGHT && inputs.exitOpen() && initialDistance < 25) bump = new RamPerson(actor);
            boolean tame = id.companion() || id == Identity.STOCK;
            switch (choice) {
                case FREEZE -> hold(YukkuriAction.BRACE, inputs.baby() ? "cry" : "horrified", "hurt_freeze", inputs.freezeTicks());
                case PUFF_FIGHT -> hold(YukkuriAction.PUFF, "angry", tame || body.getRandom().nextBoolean() ? "puff" : "hurt_defy", 50);
                case PLEAD -> hold(YukkuriAction.BEG, "cry", tame ? "hurt_tame_plea" : "plea_sad", 48);
                case ENDURE -> hold(YukkuriAction.STAND_GROUND, inputs.baby() ? "cry" : "teary", "hurt_endure", 40);
                default -> throw new IllegalStateException("Unimplemented family stance " + choice);
            }
        }
        GameplayLog.event(body, "threat", "struck stance={} attacker={} loss={} exit={} duration={} dialogue={}",
                choice, danger.getUUID(), loss, inputs.exitOpen(), choice == StruckStance.FLEE ? body.newbornStage() ? 96 : 104 : until - start,
                body.getDialogueKey());
        return choice;
    }

    private void hold(YukkuriAction action, String face, String line, int duration) {
        until = start + duration;
        body.getLookControl().setLookAt(actor, 30, 30);
        body.struckReaction(action, face, line, duration);
    }

    void flee(LivingEntity danger) {
        clear();
        int ticks = danger == null ? 90 : body.newbornStage() ? 96 : 104;
        body.setLastHurtByMob(danger);
        body.setPanicTicks(ticks);
        body.mind().feeling().frightened(danger instanceof YukkuriEntity peer && peer.profile().dangerToYukkuri() ? 1 : .6f);
        body.struckReaction(YukkuriAction.STARTLE, body.newbornStage() ? "cry" : body.body().weak() ? "teary" : "surprise",
                body.newbornStage() || danger == null ? "wounded" : "hurt_flee", ticks);
    }

    void clear() {
        if (stance != null) body.motor().cancel(this, "stance_ended");
        stance = null;
        actor = null;
        bump = null;
    }

    /** Called only by the goal holding MOVE/LOOK, including its last tick. */
    boolean tick() {
        long now = body.level().getGameTime();
        if (stance == null || !actor.isAlive() || actor.level() != body.level()) { clear(); return false; }
        double distance = body.distanceToSqr(actor);
        boolean expired = now >= until;
        if (stance == StruckStance.FREEZE && (expired && distance < 144 ||
                StruckStance.breaksFreeze((int)(now - start), distance, initialDistance, true)) && exitOpen(actor)) {
            GameplayLog.event(body, "threat", "freeze broke into flight elapsed={} attacker={}", now - start, actor.getUUID());
            flee(actor);
            return false;
        }
        if (expired) { clear(); return false; }
        body.getLookControl().setLookAt(actor, 30, 30);
        if (bump != null && now - start >= YukkuriAction.PUFF.durationTicks() / 2) {
            if (bump.check(body).ok()) {
                body.motor().run(this, bump);
                bump = null;
            } else if (distance >= 25 || SurfaceApproach.walkResult(body, RamPerson.approach(body, actor), actor, 1.2)
                    == SurfaceApproach.Result.BLOCKED) bump = null;
        } else if (body.motor().current() == null) SurfaceApproach.halt(body);
        return true;
    }

    YukkuriAction action() {
        return stance == StruckStance.FREEZE && body.level().getGameTime() - start >= YukkuriAction.BRACE.durationTicks()
                ? YukkuriAction.TREMBLE : body.reactionAction();
    }
}
