package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Gesture;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A parent who has lost sight of her dependent child calls for it, and the child that hears answers (project design, user
 * 2026-11-07 life presentation). The search walk itself is {@link FamilyLife}'s, its time limit {@link FamilySeparation}'s.
 *
 * <p>Parent: while she searches and the child is out of sight, she stops, turns toward where the family
 * last knew the child (the ledger position), calls ({@code call_child}) and listens ({@code look_around}). An answer sends her on at
 * once; silence makes her droop ({@code droop}) before she walks on. Warm parents call more rounds and sooner again, cold ones fewer and
 * later. Every pause lasts its clip.</p>
 *
 * <p>Child: a dependent child that hears its own parent's call within {@link #EARSHOT} and cannot see her turns to her, answers
 * ({@code boat_answer_call}), hops if it can, and sets out for where the call came from. It answers each call once; a baby kept to
 * its nest stays home instead. Transient: a reload starts with no call under way.</p>
 */
final class ChildCall {
    enum Phase { NONE, CALL, LISTEN, DROOP, ANSWER }
    /** How far a call carries, over open ground and through glass (blocks; project design). */
    static final double EARSHOT = 24;
    private final FamilyLife o;
    private Phase phase = Phase.NONE;
    private int ticks, rounds;
    /** Parent: the child her rounds count for, the tick her current call began and the earliest tick of the next one. */
    private UUID called;
    private long callStart = -1, nextCall;
    /** Child: the latest call answered for each parent, where the current answer came from, and whether its hop was asked for. */
    private final Map<UUID,Long> answeredCalls = new HashMap<>(2);
    private Vec3 heardFrom;
    private boolean hopped;
    private YukkuriEntity glassParent;
    private Walk glassApproach;
    /** The turn and the answering hop belong to the call while a phase runs. */
    private final MotorOwner body = new MotorOwner() {
        @Override public String ownerId() { return "family_call"; }
        @Override public boolean active() { return phase != Phase.NONE; }
        @Override public void finished(Action action, Outcome outcome) {
            if (action==glassApproach && outcome instanceof Outcome.Done)
                GameplayLog.event(o.entity(),"family","glass child at stand parent={}",glassParent.getUUID());
        }
    };

    ChildCall(FamilyLife owner) { o = owner; }

    String describe() {
        return phase + " ticks=" + ticks + " rounds=" + rounds + "/" + rounds(o.entity()) + " nextCall=" + nextCall
                + (heardFrom == null ? "" : " heardFrom=" + GameplayLog.at(heardFrom));
    }

    /** The behaviour shown while a phase holds the body, else null. */
    String activity() {
        return switch (phase) {
            case CALL -> Behaviors.CALL_CHILD;
            case LISTEN -> Behaviors.CALL_LISTEN;
            case DROOP -> Behaviors.CALL_UNANSWERED;
            case ANSWER -> waitingAtGlass() && ticks>length(Behaviors.ANSWER_CALL) ? Behaviors.CALL_LISTEN : Behaviors.ANSWER_CALL;
            case NONE -> null;
        };
    }

    private static int length(String behavior) { return EcologyPresentation.duration(behavior); }

    /** Call rounds a parent makes for one missing child: one when cold, up to four when warm (project design). */
    private static int rounds(YukkuriEntity parent) { return 1 + Math.round(3 * parent.life().personality().friendliness() / 100f); }

    /** Ticks from the end of one call round to the next: 10 s when warm, 30 s when cold (project design). */
    private static int interval(YukkuriEntity parent) { return 200 + 4 * (100 - parent.life().personality().friendliness()); }

    /** Where the family last knew the child: its ledger position in this dimension, else where it is. */
    private Vec3 lastKnown(YukkuriEntity child) {
        var known = FamilyData.get(o.level()).person(child.getUUID());
        return known != null && known.position != null && known.position.dimension().equals(o.level().dimension())
                ? Vec3.atBottomCenterOf(known.position.pos()) : child.position();
    }

    private void turnToward(Vec3 at) {
        var e = o.entity();
        float yaw = SurfaceApproach.yaw(e.position(), at);
        if (Math.abs(Mth.wrapDegrees(yaw - e.yBodyRot)) > Face.FACE_ARRIVED) e.motor().run(body, new Face(yaw));
    }

    // ------------------------------------------------------------------------------------------------ the parent

    /** The child it searches for was seen again or reunited: the next loss starts with a fresh set of rounds. */
    void found(UUID child) {
        if (child != null && child.equals(called)) { called = null; rounds = 0; nextCall = 0; }
    }

    /**
     * The parent's own pauses in her search for {@code child}: true while one holds her still (her walk waits). A call starts only on
     * the ground, out of the child's sight, between her reactions and within her rounds; her search walks on between calls.
     */
    boolean tickParent(YukkuriEntity child) {
        var e = o.entity();
        long now = o.level().getGameTime();
        if (phase == Phase.NONE) {
            if (!child.getUUID().equals(called)) { called = child.getUUID(); rounds = 0; nextCall = 0; }
            if (now < nextCall || rounds >= rounds(e) || !e.onGround() || e.hasReaction() || FamilySeparation.near(e, child)
                    || e.hasLineOfSight(child)) return false;
            Vec3 toward = lastKnown(child);
            phase = Phase.CALL; ticks = 0; rounds++; callStart = now;
            SurfaceApproach.halt(e);
            turnToward(toward);
            e.speak("wait_family");
            GameplayLog.event(e, "family", "child call started child={} round={}/{} lastKnown={} distance={} friendliness={}",
                    child.getUUID(), rounds, rounds(e), GameplayLog.at(toward), Math.round(e.distanceTo(child) * 10) / 10.0,
                    e.life().personality().friendliness());
            return true;
        }
        if (phase == Phase.ANSWER) return false;
        SurfaceApproach.halt(e);
        ticks++;
        boolean answered = child.family().call().answered(e.getUUID(), callStart);
        if (FamilySeparation.near(e, child) || e.hasLineOfSight(child)) { walkOn(now, "in_sight", child); return false; }
        switch (phase) {
            case CALL -> { if (ticks >= length(Behaviors.CALL_CHILD)) { phase = Phase.LISTEN; ticks = 0; } }
            case LISTEN -> {
                if (answered) { e.showReaction("surprise", "", 30); walkOn(now, "answered", child); return false; }
                if (ticks >= length(Behaviors.CALL_LISTEN)) {
                    phase = Phase.DROOP; ticks = 0;
                    GameplayLog.event(e, "family", "child call unanswered child={} round={}/{} distance={}",
                            child.getUUID(), rounds, rounds(e), Math.round(e.distanceTo(child) * 10) / 10.0);
                }
            }
            case DROOP -> {
                if (answered) { e.showReaction("surprise", "", 30); walkOn(now, "answered", child); return false; }
                if (ticks >= length(Behaviors.CALL_UNANSWERED)) { phase = Phase.NONE; nextCall = now + interval(e); return false; }
            }
            default -> { }
        }
        return true;
    }

    /** Once at the barrier, the same call channel keeps the pair in earshot between rounds. */
    void beginGlass(YukkuriEntity child) {
        nextCall=0;
        tickGlass(child);
    }

    void tickGlass(YukkuriEntity child) {
        var e=o.entity();long now=o.level().getGameTime();
        if (phase==Phase.CALL && ++ticks>=length(Behaviors.CALL_CHILD)) phase=Phase.LISTEN;
        if (now<nextCall) return;
        called=child.getUUID();callStart=now;nextCall=now+interval(e);
        phase=Phase.CALL;ticks=0;
        turnToward(child.position());
        boolean spoken=e.speak("glass_wait");
        GameplayLog.event(e,"family","glass call child={} spoken={}",called,spoken);
    }

    private void walkOn(long now, String why, YukkuriEntity child) {
        var e = o.entity();
        GameplayLog.event(e, "family", "child call ended why={} child={} round={}/{} phase={} distance={}",
                why, child.getUUID(), rounds, rounds(e), phase, Math.round(e.distanceTo(child) * 10) / 10.0);
        phase = Phase.NONE; nextCall = now + interval(e);
    }

    /** The calling phase other bodies can hear: the call itself, and the listening after it. */
    private boolean audible() { return phase == Phase.CALL || phase == Phase.LISTEN; }

    // ------------------------------------------------------------------------------------------------ the child

    /** This child answered the call {@code parent} began at {@code start}. */
    boolean answered(UUID parent, long start) { return start >= 0 && start == answeredCalls.getOrDefault(parent, -1L); }

    /** A call of its own parent it hears now (within earshot, out of sight) and has not answered yet, else null. */
    YukkuriEntity heard() {
        var e = o.entity();
        if (o.adult() || phase != Phase.NONE && (!waitingAtGlass() || ticks<=length(Behaviors.ANSWER_CALL))
                || e.isPassenger() || e.life().isSleeping() || e.isPanicking()) return null;
        for (UUID id : new UUID[]{o.mother(), o.father()}) {
            if (id == null || !(o.level().getEntity(id) instanceof YukkuriEntity parent) || !parent.isAlive()) continue;
            var call = parent.family().call();
            if (call.audible() && !answered(id, call.callStart) && e.distanceToSqr(parent) <= EARSHOT * EARSHOT
                    && (!parent.family().glass().callingTo(e) || e.speechEnd()<=o.level().getGameTime())
                    && !FamilySeparation.near(e, parent) && !e.hasLineOfSight(parent)) return parent;
        }
        return null;
    }

    /** It answers {@code parent}'s call: turns to her and calls back; {@link #tickAnswer} then hops, and its search walks on. */
    Vec3 answer(YukkuriEntity parent) {
        var e = o.entity();
        answeredCalls.put(parent.getUUID(), parent.family().call().callStart);
        heardFrom = parent.position();
        var glass=parent.family().glass();
        var previous=glassParent;
        glassParent=glass.callingTo(e)?parent:null;
        if (glassParent!=previous && glassParent!=null) {
            var stand=glass.childStand(e);
            glassApproach=stand==null?null:Walk.stand(stand,1);
        }
        phase = Phase.ANSWER; ticks = 0;
        if (glassParent==null || glassParent!=previous) hopped=false;
        if (glassApproach==null || e.motor().current()!=glassApproach) {
            SurfaceApproach.halt(e);
            turnToward(heardFrom);
        }
        boolean spoken=e.speak(glassParent==null?"lost_parent":"glass_child_answer");
        GameplayLog.event(e, "family", "child heard parent call parent={} distance={} heardFrom={}",
                parent.getUUID(), Math.round(e.distanceTo(parent) * 10) / 10.0, GameplayLog.at(heardFrom));
        if (glassParent!=null) {
            GameplayLog.event(e,"family","glass answer parent={} spoken={}",parent.getUUID(),spoken);
            return e.position();
        }
        return heardFrom;
    }

    boolean waitingAtGlass() { return glassParent!=null && glassParent.family().glass().callingTo(o.entity()); }

    /** True while the answer holds the child: turning and calling back for the clip, then its hop toward her while in the air. */
    boolean tickAnswer() {
        if (phase != Phase.ANSWER) return false;
        var e = o.entity();
        if (++ticks <= length(Behaviors.ANSWER_CALL)) return true;
        if (glassParent!=null) {
            if (!waitingAtGlass()) { stop(); return false; }
            if (!hopped) {
                hopped=true;
                if (glassApproach!=null) e.motor().run(body,glassApproach);
            }
            return true;
        }
        if (!hopped) {
            hopped = true;
            if (e.motor().run(body, new Gesture(Gesture.Kind.HOP_SMALL))) return true;
        }
        if (e.motor().current() instanceof Gesture && e.motor().owner() == body) return true;
        phase = Phase.NONE;
        return false;
    }

    /** The family task ended: a call or an answer under way stops here (its body actions end with it). */
    void stop() {
        if (phase == Phase.NONE) return;
        GameplayLog.limited(o.entity(), "family", "call.stop", 200, "child call interrupted phase={} ticks={}", phase, ticks);
        if (phase != Phase.ANSWER) nextCall = o.level().getGameTime() + interval(o.entity());
        o.entity().motor().cancel(body,"family_call_ended");
        glassParent=null;glassApproach=null;
        phase = Phase.NONE;
    }
}
