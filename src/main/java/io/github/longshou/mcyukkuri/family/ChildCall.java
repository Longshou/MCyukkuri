package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Gesture;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A parent who has lost sight of her dependent child calls for it, and the child that hears answers (project design, user
 * 2026-11-07 life presentation). The search walk itself is {@link FamilyLife}'s, its time limit {@link FamilySeparation}'s.
 *
 * <p>Parent: while she searches and the child is out of sight ({@link FamilySeparation#near}), she stops, turns toward where the family
 * last knew the child (the ledger position), calls ({@code call_child}) and listens ({@code look_around}). An answer sends her on at
 * once; silence makes her droop ({@code droop}) before she walks on. Warm parents call more rounds and sooner again, cold ones fewer and
 * later. Every pause lasts its clip.</p>
 *
 * <p>Child: a dependent child that hears its own parent's call within {@link #EARSHOT} and cannot see her turns to her, answers
 * ({@code boat_answer_call}), hops if it can, and sets out for where the call came from. It answers each call once. Transient: a reload
 * starts with no call under way.</p>
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
    /** Child: the parent and the call it answered last, where that call came from, and whether its hop has been asked for. */
    private UUID answeredParent;
    private long answeredCall = -1;
    private Vec3 heardFrom;
    private boolean hopped;
    /** The turn and the answering hop belong to the call while a phase runs. */
    private final MotorOwner body = new MotorOwner() {
        @Override public String ownerId() { return "family_call"; }
        @Override public boolean active() { return phase != Phase.NONE; }
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
            case ANSWER -> Behaviors.ANSWER_CALL;
            case NONE -> null;
        };
    }

    private static int length(String clip) { return ClipMarkers.of(clip).length(); }

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
            if (now < nextCall || rounds >= rounds(e) || !e.onGround() || e.hasReaction() || FamilySeparation.near(e, child)) return false;
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
        if (FamilySeparation.near(e, child)) { walkOn(now, "in_sight", child); return false; }
        switch (phase) {
            case CALL -> { if (ticks >= length(Behaviors.CALL_CHILD)) { phase = Phase.LISTEN; ticks = 0; } }
            case LISTEN -> {
                if (answered) { e.showReaction("surprise", "", 30); walkOn(now, "answered", child); return false; }
                if (ticks >= length(YukkuriAction.LOOK_AROUND.id())) {
                    phase = Phase.DROOP; ticks = 0;
                    GameplayLog.event(e, "family", "child call unanswered child={} round={}/{} distance={}",
                            child.getUUID(), rounds, rounds(e), Math.round(e.distanceTo(child) * 10) / 10.0);
                }
            }
            case DROOP -> {
                if (answered) { e.showReaction("surprise", "", 30); walkOn(now, "answered", child); return false; }
                if (ticks >= length(YukkuriAction.DROOP.id())) { phase = Phase.NONE; nextCall = now + interval(e); return false; }
            }
            default -> { }
        }
        return true;
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
    boolean answered(UUID parent, long start) { return start >= 0 && start == answeredCall && parent.equals(answeredParent); }

    /** A call of its own parent it hears now (within earshot, out of sight) and has not answered yet, else null. */
    YukkuriEntity heard() {
        var e = o.entity();
        if (o.adult() || phase != Phase.NONE || e.life().isSleeping() || e.isPanicking()) return null;
        for (UUID id : new UUID[]{o.mother(), o.father()}) {
            if (id == null || !(o.level().getEntity(id) instanceof YukkuriEntity parent) || !parent.isAlive()) continue;
            var call = parent.family().call();
            if (call.audible() && !answered(id, call.callStart) && e.distanceToSqr(parent) <= EARSHOT * EARSHOT
                    && !FamilySeparation.near(e, parent)) return parent;
        }
        return null;
    }

    /** It answers {@code parent}'s call: turns to her and calls back; {@link #tickAnswer} then hops, and its search walks on. */
    Vec3 answer(YukkuriEntity parent) {
        var e = o.entity();
        answeredParent = parent.getUUID(); answeredCall = parent.family().call().callStart;
        heardFrom = parent.position();
        phase = Phase.ANSWER; ticks = 0; hopped = false;
        SurfaceApproach.halt(e);
        turnToward(heardFrom);
        e.speak("lost_parent");
        GameplayLog.event(e, "family", "child heard parent call parent={} distance={} heardFrom={}",
                parent.getUUID(), Math.round(e.distanceTo(parent) * 10) / 10.0, GameplayLog.at(heardFrom));
        return heardFrom;
    }

    /** True while the answer holds the child: turning and calling back for the clip, then its hop toward her while in the air. */
    boolean tickAnswer() {
        if (phase != Phase.ANSWER) return false;
        var e = o.entity();
        if (++ticks <= length(Behaviors.BOAT_ANSWER_CALL)) { SurfaceApproach.halt(e); return true; }
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
        phase = Phase.NONE;
    }
}
