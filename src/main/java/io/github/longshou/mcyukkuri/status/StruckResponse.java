package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.RamPerson;
import io.github.longshou.mcyukkuri.settlement.DenSeal;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** The victim's response window. ThreatGoal steers a held stance and FleeGoal owns flight. */
final class StruckResponse implements MotorOwner {
    private final YukkuriEntity body;
    private LivingEntity actor;
    private StruckStance stance;
    private long start, until;
    private double initialDistance;
    private RamPerson bump;
    private Vec3 mouth;
    private long nextTaunt;

    StruckResponse(YukkuriEntity body) { this.body = body; }
    @Override public String ownerId() { return Behaviors.STRUCK; }
    @Override public boolean active() {
        return stance != null && body.level().getGameTime() < until && actor.isAlive() && actor.level() == body.level();
    }

    boolean leading() { return stance == StruckStance.LEAD_AWAY; }

    private boolean exitOpen(LivingEntity danger) {
        return Reactions.freeToReact(body) && !body.isLeashed() && !body.grief().protecting()
                && !body.shellRetraction().available() && body.status().reactions().threat().flight().hasExit(danger);
    }

    StruckStance decide(LivingEntity danger, float loss) {
        var seal = body.status().reactions().threat().seal();
        boolean finish = seal.holdsThroughHit();
        if (finish || !seal.active() && seal.tryCommit(danger, DenSeal.Side.INSIDE)) {
            if (finish) {
                var home = body.habitat().shelter();
                seal.commit(danger, home.contains(body.blockPosition()) ? DenSeal.Side.INSIDE : DenSeal.Side.OUTSIDE);
                startFlight(danger);
                body.struckReaction(EcologyPresentation.action(Behaviors.NEST_PRESS), "teary", "hurt_endure", 20);
            } else flee(danger);
            GameplayLog.event(body, "threat", "struck stance=SEAL attacker={} loss={}", danger.getUUID(), loss);
            return StruckStance.SEAL;
        }
        if (homeTactic(danger)) return StruckStance.LEAD_AWAY;
        var p = body.life().personality();
        var id = body.status().identity();
        int young = body.level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(6), body.family()::caresFor).size();
        var inputs = new StruckStance.Inputs(p.temperament(),
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
            }
        }
        GameplayLog.event(body, "threat", "struck stance={} attacker={} loss={} exit={} duration={} dialogue={}",
                choice, danger.getUUID(), loss, inputs.exitOpen(), choice == StruckStance.FLEE ? body.newbornStage() ? 96 : 104 : until - start,
                body.getDialogueKey());
        return choice;
    }

    private List<YukkuriEntity> dependants(ShelterBlockEntity home) {
        return body.level().getEntitiesOfClass(YukkuriEntity.class, home.interior(), child -> child.isAlive()
                && home.owns(child.getUUID()) && home.contains(child.blockPosition()) && body.family().caresFor(child));
    }

    boolean homeTactic(LivingEntity danger) {
        var home = body.habitat().shelter();
        if (!(body.family().adult() || body.family().subadult()) || body.body().weak() || !Reactions.freeToReact(body)
                || body.isLeashed() || home == null || !home.owns(body.getUUID()) || home.contains(body.blockPosition())) return false;
        var door = Vec3.atBottomCenterOf(home.entrance());
        if (body.distanceToSqr(door) > 256) return false;
        var p = body.life().personality();
        var t = p.temperament();
        int young = dependants(home).size();
        double score = HomeTactic.lead(t, p.fear(danger.getUUID()), body.body().healthRatio(), young);
        if (score < HomeTactic.LEAD_AT) return false;
        if (stance == StruckStance.LEAD_AWAY && actor == danger && active()) return true;
        clear();
        actor = danger;
        stance = StruckStance.LEAD_AWAY;
        mouth = door;
        body.status().reactions().threat().flight().shield(danger, door);
        start = body.level().getGameTime();
        until = start + HomeTactic.leadTicks(t);
        nextTaunt = start;
        body.setPanicTicks(0);
        var seal = body.status().reactions().threat().seal();
        boolean first = !seal.active() && seal.tryCommit(danger, DenSeal.Side.OUTSIDE);
        GameplayLog.event(body, "threat", "home tactic stance=LEAD_AWAY score={} dependants={} sealFirst={} den={}",
                score, young, first, home.getBlockPos());
        return true;
    }

    private boolean leadTick(long now) {
        var t = body.life().personality().temperament();
        double fromDen = body.position().distanceTo(mouth);
        String end = !actor.isAlive() || actor.level() != body.level() || body.distanceToSqr(actor) > 576 ? "threat_lost"
                : fromDen >= HomeTactic.leadRange(t) && actor.distanceToSqr(mouth) >= 144 ? "led_away"
                : now >= until ? "expired" : null;
        if (end != null) {
            GameplayLog.event(body, "threat", "lead away ended reason={} fromDen={} elapsed={}", end, fromDen, now - start);
            clear();
            return false;
        }
        var threat = body.status().reactions().threat();
        if (threat.seal().committedOutside() && threat.seal().drive(actor)) return true;
        int clip = YukkuriAction.PUKU_TEARY.durationTicks();
        if (now >= nextTaunt && body.distanceTo(actor) > HomeTactic.tauntGap(t)) {
            SurfaceApproach.halt(body);
            body.getLookControl().setLookAt(actor, 30, 30);
            body.showReaction(YukkuriAction.PUKU_TEARY, "puff_teary", "protect_taunt", clip);
            var home = body.habitat().shelter();
            if (home != null && actor instanceof YukkuriEntity predator && predator.profile().dangerToYukkuri())
                for (var child : dependants(home))
                    if (predator.predator().divert(body, child)) break;
            GameplayLog.limited(body, "threat", "lead_taunt", 60, "lead away taunt distance={} attacker={}",
                    body.distanceTo(actor), actor.getUUID());
            nextTaunt = now + clip * 2L;
        } else if (now >= nextTaunt - clip) threat.flight().lead(actor, mouth);
        return true;
    }

    private void hold(YukkuriAction action, String face, String line, int duration) {
        until = start + duration;
        body.getLookControl().setLookAt(actor, 30, 30);
        body.struckReaction(action, face, line, duration);
    }

    private int startFlight(LivingEntity danger) {
        clear();
        int ticks = danger == null ? 90 : body.newbornStage() ? 96 : 104;
        body.setLastHurtByMob(danger);
        body.setPanicTicks(ticks);
        // A struck baby cries through the flight, then can follow kin or settle without the older body's terror course.
        if (!body.newbornStage())
            body.mind().feeling().frightened(danger instanceof YukkuriEntity peer && peer.profile().dangerToYukkuri() ? 1 : .6f);
        return ticks;
    }

    void flee(LivingEntity danger) {
        int ticks = startFlight(danger);
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
        if (stance == StruckStance.LEAD_AWAY) return leadTick(now);
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
        if (stance == StruckStance.LEAD_AWAY) return body.level().getGameTime() < nextTaunt - YukkuriAction.PUKU_TEARY.durationTicks()
                ? YukkuriAction.PUKU_TEARY : YukkuriAction.RUN;
        return stance == StruckStance.FREEZE && body.level().getGameTime() - start >= YukkuriAction.BRACE.durationTicks()
                ? YukkuriAction.TREMBLE : body.reactionAction();
    }
}
