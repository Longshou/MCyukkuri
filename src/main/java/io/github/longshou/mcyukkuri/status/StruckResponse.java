package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.component.Interrupt;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
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

    boolean fighting() { return active() && stance == StruckStance.PUFF_FIGHT; }
    boolean leadingFrom(LivingEntity danger) { return stance == StruckStance.LEAD_AWAY && actor == danger && active(); }

    private boolean exitOpen(LivingEntity danger) {
        return Reactions.freeToReact(body) && !body.isLeashed() && !body.grief().protecting()
                && !body.shellRetraction().available() && (body.status().reactions().threat().airEscape(danger)
                ? body.predator().hasAirExit() : body.status().reactions().threat().flight().hasExit(danger));
    }

    StruckStance decide(LivingEntity danger, float loss) {
        var seal = body.status().reactions().threat().seal();
        boolean finish = seal.holdsThroughHit();
        if ((!finish || leadingFrom(danger)) && homeTactic(danger)) {
            GameplayLog.event(body, "threat", "struck stance=LEAD_AWAY attacker={} loss={}", danger.getUUID(), loss);
            return StruckStance.LEAD_AWAY;
        }
        if (finish || !seal.active() && seal.tryCommit(danger, DenSeal.Side.INSIDE)) {
            if (finish) {
                var home = body.habitat().shelter();
                seal.commit(danger, home.contains(body.blockPosition()) ? DenSeal.Side.INSIDE : DenSeal.Side.OUTSIDE);
                startFlight(danger);
                body.struckReaction(EcologyPresentation.action(Behaviors.NEST_PRESS), "teary",
                        ReactionSpeech.struck(StruckStance.SEAL, loss > 0, false, false), 20);
            } else startRetreat(danger, ReactionSpeech.struck(StruckStance.SEAL, loss > 0, false, false));
            GameplayLog.event(body, "threat", "struck stance=SEAL attacker={} loss={}", danger.getUUID(), loss);
            return StruckStance.SEAL;
        }
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
            if (loss == 0) {
                body.interrupt(Interrupt.FRIGHT);
                body.life().involuntaryRelease("fright", false);
            }
            flee(danger, loss > 0);
        }
        else {
            actor = danger;
            stance = choice;
            start = body.level().getGameTime();
            initialDistance = body.distanceToSqr(actor);
            if (choice == StruckStance.PUFF_FIGHT && inputs.exitOpen() && initialDistance < 25) bump = new RamPerson(actor);
            String line = ReactionSpeech.struck(choice, loss > 0, ReactionSpeech.trustsCareFrom(id, inputs.trustedOwner()), bump != null);
            switch (choice) {
                case FREEZE -> hold(YukkuriAction.BRACE, inputs.baby() ? "cry" : "horrified", line, inputs.freezeTicks());
                case PUFF_FIGHT -> hold(YukkuriAction.PUFF, "angry", line, 50);
                case PLEAD -> hold(YukkuriAction.BEG, "cry", line, 48);
                case ENDURE -> hold(YukkuriAction.STAND_GROUND, inputs.baby() ? "cry" : "teary", line, 40);
                case FLEE, LEAD_AWAY, SEAL -> throw new IllegalStateException("Cannot hold " + choice);
            }
            if (!inputs.baby()) {
                var feeling = body.mind().feeling();
                float power = threatPower(danger);
                if (choice == StruckStance.PUFF_FIGHT) {
                    feeling.begin(EmotionCourse.Kind.RAGE, power, danger.getUUID(), body.blockPosition());
                } else {
                    body.setLastHurtByMob(danger);
                    feeling.frightened(power);
                }
            }
        }
        GameplayLog.event(body, "threat", "struck stance={} attacker={} loss={} exit={} identity={} duration={} dialogue={}",
                choice, danger.getUUID(), loss, inputs.exitOpen(), id.id(),
                choice == StruckStance.FLEE ? body.newbornStage() ? 96 : 104 : until - start, body.getDialogueKey());
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
        boolean continuing = leadingFrom(danger);
        if (!continuing && body.distanceToSqr(door) > 256) return false;
        var p = body.life().personality();
        var t = p.temperament();
        int young = dependants(home).size();
        double score = HomeTactic.lead(t, p.fear(danger.getUUID()), body.body().healthRatio(), young);
        if (score < HomeTactic.LEAD_AT) return false;
        if (continuing) return true;
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
        int clip = YukkuriAction.PUFF_TEARY.durationTicks();
        if (now >= nextTaunt && body.distanceTo(actor) > HomeTactic.tauntGap(t)) {
            SurfaceApproach.halt(body);
            body.getLookControl().setLookAt(actor, 30, 30);
            var home = body.habitat().shelter();
            var young = home == null ? List.<YukkuriEntity>of() : dependants(home);
            body.showReaction(YukkuriAction.PUFF_TEARY, "puff_teary", ReactionSpeech.leadTaunt(!young.isEmpty()), clip, FaceTier.Tier.ofTicks(clip));
            if (actor instanceof YukkuriEntity predator && predator.profile().dangerToYukkuri())
                for (var child : young)
                    if (predator.predator().divert(body, child)) break;
            GameplayLog.limited(body, "threat", "lead_taunt", 60, "lead away taunt distance={} attacker={}", () -> new Object[]{
                        body.distanceTo(actor), actor.getUUID()});
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
        if (!body.newbornStage()) body.mind().feeling().frightened(threatPower(danger));
        return ticks;
    }

    private static float threatPower(LivingEntity danger) {
        return danger instanceof YukkuriEntity peer && peer.profile().dangerToYukkuri() ? 1 : .6f;
    }

    private String startledFace() {
        return body.newbornStage() ? "cry" : body.body().weak() ? "teary" : "surprise";
    }

    private void startRetreat(LivingEntity danger, String line) {
        int ticks = startFlight(danger);
        if (body.status().reactions().threat().airEscape(danger)) body.predator().withdraw(danger);
        body.struckReaction(YukkuriAction.STARTLE, startledFace(), line, ticks);
    }

    void flee(LivingEntity danger, boolean hurt) {
        String line = hurt && (body.newbornStage() || danger == null)
                ? "wounded" : ReactionSpeech.struck(StruckStance.FLEE, hurt, false, false);
        startRetreat(danger, line);
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
            body.interruptSpeech();
            flee(actor, false);
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
        if (stance == StruckStance.LEAD_AWAY) {
            if (body.level().getGameTime() < nextTaunt - YukkuriAction.PUFF_TEARY.durationTicks()) return YukkuriAction.PUFF_TEARY;
            return body.status().reactions().threat().flight().cornered(actor) ? YukkuriAction.TREMBLE : YukkuriAction.RUN;
        }
        return stance == StruckStance.FREEZE && body.level().getGameTime() - start >= YukkuriAction.BRACE.durationTicks()
                ? YukkuriAction.TREMBLE : body.reactionAction();
    }
}
