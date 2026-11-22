package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Desire;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.mind.Ousting;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.SeveranceVerdict.Verdict;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

/**
 * The parent's choice after her hull went over from its load: the child {@link AquaticLife#overloadCapsized} picked is dealt with
 * in view. Once she stands on support (ashore, or on her righted hull) with the child in sight she turns to it and holds the look:
 * a keeper smiles through tears and stays ({@code keep_child}); a tearful driver says her sorry ({@code drive_child_tearful}), and
 * cuts the tie once the child stands ashore; a cold one flicks her face away and cuts at once, the water deciding the child's fate.
 * At the end of her words the child answers by its own temperament ({@link Ousting#answer}) and its siblings in sight cry, she
 * keeps looking a moment, and only then falls the cut ({@link Bonds#sever}), so the one-sided departure is seen in that order.
 * The same look (no cut, no line) is played for the young her full hull leaves on the bank as she sets out ({@link #leftAshore}). A verdict not played out within {@link #MEMO} ticks
 * (child or parent out of reach, an incident stepping in) is dropped without a cut and the next capsize draws a new one.
 */
final class LeaveBehind implements Intent {
    /** The beat a keeper's or a tearful parent's look lasts; a cold one's (ticks); how far past it the intent may run. */
    private static final int WARM_BEAT = 80, COLD_BEAT = 50, GRACE = 40;
    /** How long she holds the look after the child answered, before the cut (ticks): the answer and the siblings' cry are seen first. */
    private static final int HEAR_WARM = 60, HEAR_COLD = 40;
    /** How long a drawn verdict waits to be played out (ticks); how many interrupted beats it survives; how far the child may be (blocks). */
    private static final int MEMO = 3600, TRIES = 2, SIGHT = 16, SIBLING_SIGHT = 12;

    /** A verdict already drawn at a capsize, waiting for the parent to stand where she can play it out (never saved). */
    private static final class Memo {
        final UUID child;
        final Verdict verdict;
        final long until;
        /** A child her full hull left standing on the bank (no cut, no line: her face and its own answer only). */
        final boolean ferry;
        int tries;
        /** The child's answer, once it was shown (a retried beat does not repeat it). */
        Ousting.Answer reply;

        Memo(UUID child, Verdict verdict, long until, boolean ferry) {
            this.child = child;
            this.verdict = verdict;
            this.until = until;
            this.ferry = ferry;
        }
    }

    private static final Map<YukkuriEntity, Memo> MEMOS = Collections.synchronizedMap(new WeakHashMap<>());

    static void remember(YukkuriEntity parent, YukkuriEntity child, Verdict verdict) {
        MEMOS.put(parent, new Memo(child.getUUID(), verdict, parent.level().getGameTime() + MEMO, false));
    }

    /** Her hull is too full for this young and she sets out without it: she looks back at it from the hull (a verdict still owed from
     *  a capsize goes first). The same trade-off as the capsize, played where the hull's load rule already decided. */
    static void leftAshore(YukkuriEntity parent, YukkuriEntity child) {
        MEMOS.putIfAbsent(parent, new Memo(child.getUUID(), Verdict.KEEP, parent.level().getGameTime() + MEMO, true));
    }

    /** The child whose verdict she still owes, or null (and the verdict dropped, with its reason). */
    private static YukkuriEntity subject(YukkuriEntity parent, Memo memo) {
        var level = (ServerLevel) parent.level();
        var child = level.getEntity(memo.child) instanceof YukkuriEntity y ? y : null;
        String lapsed = level.getGameTime() > memo.until ? "timeout"
                : memo.tries >= TRIES ? "interrupted"
                : child == null || !child.isAlive() ? "child_gone"
                : !Bonds.parentOf(parent, child) ? "not_acknowledged"
                : child.getVehicle() instanceof PlacedHatEntity ? "child_aboard" : null;
        if (lapsed == null) return child;
        MEMOS.remove(parent);
        GameplayLog.event(parent, "family", "overload verdict expired reason={} child={} verdict={}", lapsed, memo.child, memo.verdict);
        return null;
    }

    /** Offered while a verdict is owed, the parent stands on support and the child is in sight; a tearful driver waits until the
     *  child stands ashore (the rescue and its own climb bring it there), a keeper or a cold one does not. */
    static void offer(List<Desire> out, YukkuriEntity parent, boolean steady) {
        var memo = MEMOS.get(parent);
        if (memo == null) return;
        var child = subject(parent, memo);
        if (child == null || !steady || child.isPassenger() || parent.distanceTo(child) > SIGHT || !parent.hasLineOfSight(child)
                || memo.verdict == Verdict.DRIVE_TEARFUL && !AquaticLife.ashore(child)) return;
        out.add(Desire.of("leave_behind", AquaticGoal.num("leave_want", 100), List.of(memo.child, memo.verdict, memo.tries),
                () -> "child=" + memo.child + " verdict=" + memo.verdict, () -> new LeaveBehind(parent, child, memo)));
    }

    private final YukkuriEntity parent, child;
    private final Memo memo;
    private final long started;
    private final int beat, hear;

    private LeaveBehind(YukkuriEntity parent, YukkuriEntity child, Memo memo) {
        this.parent = parent;
        this.child = child;
        this.memo = memo;
        started = parent.level().getGameTime();
        beat = memo.verdict == Verdict.DRIVE_COLD ? COLD_BEAT : WARM_BEAT;
        // a kept child (not left ashore by the hull) answers nothing: only a cut or a hull-left child is heard from
        hear = memo.verdict == Verdict.KEEP && !memo.ferry ? 0 : memo.verdict == Verdict.DRIVE_COLD ? HEAR_COLD : HEAR_WARM;
        switch (memo.verdict) {
            case KEEP -> { if (parent.showReaction("puff_teary", "", beat) && !memo.ferry) parent.speakCue("keep_child"); }
            case DRIVE_TEARFUL -> { if (parent.showReaction("puff_teary", "", beat)) parent.speakCue("drive_child_tearful"); }
            case DRIVE_COLD -> parent.showReaction(YukkuriAction.MOOD_FLICK, "angry", "", beat);
        }
        GameplayLog.event(parent, "family", "overload beat start child={} verdict={} ferry={} distance={} child_in_water={}",
                child.getUUID(), memo.verdict, memo.ferry, Math.round(parent.distanceTo(child)), child.isInWaterOrBubble());
    }

    @Override public String id() { return "leave_behind"; }
    @Override public long deadline() { return started + beat + hear + GRACE; }
    @Override public Commitment commitment() { return Commitment.HARD; }
    @Override public boolean satisfied() { return parent.level().getGameTime() - started >= beat + hear; }
    @Override public List<Method> methods() { return List.of(Plan.of("leave_behind", this::derive)); }

    private float yaw() { return SurfaceApproach.yaw(parent.position(), child.position()); }

    /** She turns to the child and holds the look; at the end of her words the child answers and its siblings cry, she keeps looking
     *  a moment, and only then does the intent end (and the cut fall). */
    private List<Supplier<Step>> derive() {
        return List.of(() -> new Step.Do(new Face(yaw())),
                () -> new Step.Wait(() -> parent.level().getGameTime() - started >= beat, beat + GRACE, null),
                () -> {
                    if (hear == 0) return null;
                    answer();
                    return new Step.Wait(this::satisfied, hear + GRACE, null);
                });
    }

    @Override public void ended(IntentRunner.Result result) {
        if (result != IntentRunner.Result.DONE) {
            memo.tries++;
            GameplayLog.event(parent, "family", "overload beat interrupted child={} result={} tries={}", child.getUUID(), result, memo.tries);
            return;
        }
        MEMOS.remove(parent);
        if (memo.verdict == Verdict.KEEP) {
            GameplayLog.event(parent, "family", "{} child={} verdict=KEEP", memo.ferry ? "overload leaves child ashore" : "overload keeps", child.getUUID());
            return;
        }
        if (!child.isAlive() || child.getVehicle() instanceof PlacedHatEntity) {
            GameplayLog.event(parent, "family", "overload verdict expired reason={} child={} verdict={}",
                    child.isAlive() ? "child_aboard" : "child_gone", child.getUUID(), memo.verdict);
            return;
        }
        Bonds.sever(parent, child.getUUID(), "boat_overload");
        if (memo.verdict == Verdict.DRIVE_TEARFUL) {
            parent.mind().feeling().begin(EmotionCourse.Kind.PARTING, .5f, child.getUUID(), child.blockPosition());
            parent.showReaction("teary", "mood_bad", 120);
        }
        GameplayLog.event(parent, "family", "overload severed child={} verdict={} answer={} fumes={} in_water={}",
                child.getUUID(), memo.verdict, memo.reply, memo.reply == Ousting.Answer.CURSE, child.isInWaterOrBubble());
    }

    /** The child answers by its own temperament while the parent still looks at it; a cut child is hurt and its siblings in sight cry
     *  after it, a child the hull left ashore only shows what it feels (no voice line, no hurt, nobody else cries). */
    private void answer() {
        if (memo.reply != null) return;
        memo.reply = Ousting.answer(child.life().personality().temperament());
        boolean swimming = child.isInWaterOrBubble();
        boolean shown = switch (memo.reply) {
            case CURSE -> swimming ? child.showReaction("angry", "", 60) : child.showReaction(YukkuriAction.STAMP, "angry", "", 60);
            case FAREWELL -> child.showReaction("cry", "", memo.ferry ? 60 : 40);
            case SHRUG -> child.showReaction("hmph", "", 40);
        };
        if (memo.ferry) {
            GameplayLog.event(child, "family", "overload left ashore child answers parent={} answer={}", parent.getUUID(), memo.reply);
            return;
        }
        child.mind().felt(EmotionEvent.SOCIAL_HURT);
        child.mind().feeling().wronged(parent.getUUID(), .6f);
        String cue = ReactionSpeech.ousted(memo.reply, false);
        if (shown && !cue.isEmpty()) child.speakCue(cue);
        for (var sibling : AquaticLife.young(parent)) {
            if (sibling == child || !Bonds.parentOf(parent, sibling)) continue;
            String missed = sibling.distanceToSqr(child) > SIBLING_SIGHT * SIBLING_SIGHT ? "out_of_range"
                    : !sibling.hasLineOfSight(child) ? "out_of_sight" : null;
            if (missed != null) {
                GameplayLog.event(sibling, "family", "overload child missed departure sibling={} reason={}", child.getUUID(), missed);
                continue;
            }
            sibling.showReactionDelayed("cry", "mold_child_cry", 80, 20);
            sibling.mind().feeling().begin(EmotionCourse.Kind.PARTING, .5f, child.getUUID(), child.blockPosition());
            GameplayLog.event(sibling, "family", "overload sibling cries sibling={}", child.getUUID());
        }
    }
}
