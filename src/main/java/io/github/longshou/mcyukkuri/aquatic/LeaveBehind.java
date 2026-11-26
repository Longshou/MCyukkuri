package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.family.DrivenOut;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Desire;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.mind.Ousting;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Push;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.SeveranceVerdict.Verdict;
import io.github.longshou.mcyukkuri.social.Temperament;
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
 * a keeper smiles through tears and stays ({@code keep_child}); a tearful driver says her sorry ({@code drive_child_tearful}) once
 * the child stands ashore; a cold one flicks her face away and says the hat cannot carry it at once, the water deciding the child's fate.
 * At the end of her words she gives the order and the child owns its departure ({@link DrivenOut}: it answers by its own
 * temperament, walks away, and the tie is cut only when it is truly gone). She watches it go ({@link Watch}) and turns away a pleader who
 * steps up to her: the cold one shoves it off, the tearful one turns her back.
 * The same look and one short line by her temperament (wait here, or stay), with no cut, is played for the young her full hull leaves on the bank as she sets out ({@link #leftAshore}). A verdict not played out within {@link #MEMO} ticks (not counted while she saves her young, {@link #keep})
 * (child or parent out of reach, an incident stepping in) is dropped without a cut and the next capsize draws a new one.
 */
final class LeaveBehind implements Intent {
    /** The beat a keeper's or a tearful parent's look lasts; a cold one's (ticks); how far past it the intent may run. */
    private static final int WARM_BEAT = 80, COLD_BEAT = 50, GRACE = 40;
    /** How long she holds the look after the left-ashore child answered (ticks): its answer is seen first. */
    private static final int HEAR_WARM = 60;
    /** Speech priority of her verdict words: above the rescue chatter still running as she turns. */
    private static final int SAY_PRIORITY = 4;
    /** How long a drawn verdict waits to be played out (ticks); how many interrupted beats it survives; how far the child may be (blocks). */
    private static final int MEMO = 3600, TRIES = 2, SIGHT = 16;
    /** How long she watches a child go (ticks: the plea, in which a pleader may take up to a minute to reach her, and the early walk); how far she shoves a pleader off (blocks). */
    private static final int WATCH = 2400, SHOVE = 3, PLEA_WAIT = 1500;

    /** A verdict already drawn at a capsize, waiting for the parent to stand where she can play it out (never saved). */
    private static final class Memo {
        final UUID child;
        final Verdict verdict;
        long until;
        /** A child her full hull left standing on the bank (no cut: her face, her line and its own answer only). */
        final boolean ferry;
        /** Her temperament is the cold one (harsh, not attached): the words she leaves a child ashore with. */
        final boolean coldParent;
        int tries;
        /** The order is given: the child owns its departure and she only watches it go. */
        boolean ordered;
        /** The child's answer, once it was shown (a retried beat does not repeat it). */
        Ousting.Answer reply;

        Memo(UUID child, Verdict verdict, long until, boolean ferry, boolean coldParent) {
            this.child = child;
            this.coldParent = coldParent;
            this.verdict = verdict;
            this.until = until;
            this.ferry = ferry;
        }
    }

    private static final Map<YukkuriEntity, Memo> MEMOS = Collections.synchronizedMap(new WeakHashMap<>());

    static void remember(YukkuriEntity parent, YukkuriEntity child, Verdict verdict) {
        MEMOS.put(parent, new Memo(child.getUUID(), verdict, parent.level().getGameTime() + MEMO, false, false));
    }

    /** Her hull is too full for this young and she sets out without it: she looks back at it from the hull (a verdict still owed from
     *  a capsize goes first). The same trade-off as the capsize, played where the hull's load rule already decided. */
    static void leftAshore(YukkuriEntity parent, YukkuriEntity child) {
        var t = parent.life().personality().temperament();
        MEMOS.putIfAbsent(parent, new Memo(child.getUUID(), Verdict.KEEP, parent.level().getGameTime() + MEMO, true,
                !t.is(Temperament.Gate.ATTACHED) && t.is(Temperament.Gate.HARSH)));
    }

    /** She is saving her young: a verdict still owed waits for them and does not run out meanwhile (the rescue's own deadlines bound
     *  it), so it is played out once they are safe. */
    static void keep(YukkuriEntity parent) {
        var memo = MEMOS.get(parent);
        long now = parent.level().getGameTime();
        if (memo != null && now <= memo.until) memo.until = Math.max(memo.until, now + MEMO / 2);
    }

    /** The child whose verdict she still owes (or whose departure she watches), or null (and the verdict dropped, with its reason). */
    private static YukkuriEntity subject(YukkuriEntity parent, Memo memo) {
        var level = (ServerLevel) parent.level();
        var child = level.getEntity(memo.child) instanceof YukkuriEntity y ? y : null;
        String lapsed = level.getGameTime() > memo.until ? "timeout"
                : memo.tries >= TRIES ? "interrupted"
                : child == null || !child.isAlive() ? "child_gone"
                : memo.ordered ? parent.getUUID().equals(child.drivenOut().driver()) ? null : "departure_over"
                : !Bonds.parentOf(parent, child) ? "not_acknowledged"
                : child.getVehicle() instanceof PlacedHatEntity ? "child_aboard" : null;
        if (lapsed == null) return child;
        MEMOS.remove(parent);
        GameplayLog.event(parent, "family", "overload verdict expired reason={} child={} verdict={}", lapsed, memo.child, memo.verdict);
        return null;
    }

    /** Whether this afloat parent holds a capsize drive verdict that must be played out on dry ground. */
    static boolean wantsAshore(YukkuriEntity parent) {
        var memo = MEMOS.get(parent);
        return memo != null && !memo.ferry && memo.verdict != Verdict.KEEP && !memo.ordered && subject(parent, memo) != null;
    }

    /** Offered while a verdict is owed, the parent stands on support (dry ground for capsize drive verdicts so the child can step up and
     *  be rebuffed) and the child is in sight; a tearful driver waits until the child stands ashore, a keeper or a cold one does not. Once
     *  the order is given, offered again as the watch over the child's departure, while it is on its way. */
    static void offer(List<Desire> out, YukkuriEntity parent, boolean steady) {
        var memo = MEMOS.get(parent);
        if (memo == null) return;
        var child = subject(parent, memo);
        if (child == null || !steady || parent.distanceTo(child) > SIGHT || !parent.hasLineOfSight(child)) return;
        if (memo.ordered) {
            if (child.drivenOut().phase() == DrivenOut.Phase.NONE || child.isInWaterOrBubble() || child.species().catchingBreath()) return;
            out.add(Desire.of("watch_leave", AquaticGoal.num("leave_want", 100), List.of(memo.child, memo.tries),
                    () -> "child=" + memo.child, () -> new Watch(parent, child, memo)));
            return;
        }
        if (child.isPassenger() || !memo.ferry && memo.verdict != Verdict.KEEP && !AquaticLife.ashore(parent)
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
        // only a child the hull left ashore answers on the spot; a driven child answers when it stands to go (DrivenOut)
        hear = memo.ferry ? HEAR_WARM : 0;
        String face, line;
        switch (memo.verdict) {
            case KEEP -> {
                face = "puff_teary";
                line = memo.ferry ? ReactionSpeech.leftAshore(memo.coldParent) : "keep_child";
                if (parent.showReaction(face, "", FaceTier.Tier.LONG)) say(line);
            }
            case DRIVE_TEARFUL -> {
                face = "puff_teary";
                line = "drive_child_tearful";
                if (parent.showReaction(face, "", FaceTier.Tier.LONG)) say(line);
            }
            default -> {
                face = "angry";
                line = "drive_child_cold";
                if (parent.showReaction(YukkuriAction.MOOD_FLICK, face, "", beat, FaceTier.Tier.LONG)) say(line);
            }
        }
        parent.beat("verdict", face, line);
        GameplayLog.event(parent, "family", "overload beat start child={} verdict={} ferry={} distance={} child_in_water={}",
                child.getUUID(), memo.verdict, memo.ferry, Math.round(parent.distanceTo(child)), child.isInWaterOrBubble());
    }

    /** Her verdict words take the floor over the rescue chatter that is still running as she turns (a priority-three line would
     *  otherwise leave the beat silent). */
    private void say(String event) { parent.speakCue(event, null, SAY_PRIORITY); }

    @Override public String id() { return "leave_behind"; }
    @Override public long deadline() { return started + beat + hear + GRACE; }
    @Override public Commitment commitment() { return Commitment.HARD; }
    @Override public boolean satisfied() { return parent.level().getGameTime() - started >= beat + hear; }
    @Override public List<Method> methods() { return List.of(Plan.of("leave_behind", this::derive)); }

    private float yaw() { return SurfaceApproach.yaw(parent.position(), child.position()); }

    /** She turns to the child and holds the look; a child the hull left ashore answers at the end of her words and she keeps looking a
     *  moment, and only then does the intent end. */
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
        if (memo.verdict == Verdict.KEEP) {
            MEMOS.remove(parent);
            GameplayLog.event(parent, "family", "{} child={} verdict=KEEP", memo.ferry ? "overload leaves child ashore" : "overload keeps", child.getUUID());
            return;
        }
        if (!child.isAlive() || child.getVehicle() instanceof PlacedHatEntity) {
            MEMOS.remove(parent);
            GameplayLog.event(parent, "family", "overload verdict expired reason={} child={} verdict={}",
                    child.isAlive() ? "child_aboard" : "child_gone", child.getUUID(), memo.verdict);
            return;
        }
        // The order is given; from here the child owns its departure and the cut falls only when it is truly gone.
        memo.ordered = true;
        memo.tries = 0;
        child.drivenOut().order(parent, memo.verdict, "boat_overload");
        GameplayLog.event(parent, "family", "overload orders child out child={} verdict={} in_water={}",
                child.getUUID(), memo.verdict, child.isInWaterOrBubble());
    }

    /** A child the hull left ashore answers by its own temperament while the parent still looks at it; nobody cuts and nobody else cries,
     *  only its cry after the hull is voiced. */
    private void answer() {
        if (memo.reply != null) return;
        memo.reply = Ousting.answer(child.life().personality().temperament());
        boolean swimming = child.isInWaterOrBubble();
        boolean shown = switch (memo.reply) {
            case CURSE -> swimming ? child.showReaction("angry", "", FaceTier.Tier.MEDIUM)
                    : child.showReaction(YukkuriAction.STAMP, "angry", "", YukkuriAction.STAMP.durationTicks(), FaceTier.Tier.MEDIUM);
            case FAREWELL -> child.showReaction("cry", "", FaceTier.Tier.MEDIUM);
            case SHRUG -> child.showReaction("hmph", "", FaceTier.Tier.MEDIUM);
        };
        // Only the cry after the departing hull is voiced; the sulk and the stamp stay wordless.
        boolean cry = shown && memo.reply == Ousting.Answer.FAREWELL;
        if (cry) child.speakCue("mold_child_cry", null, SAY_PRIORITY);
        child.beat("left_ashore_answer", memo.reply == Ousting.Answer.FAREWELL ? "cry" : memo.reply == Ousting.Answer.SHRUG ? "hmph" : "angry",
                cry ? "mold_child_cry" : "");
        GameplayLog.event(child, "family", "overload left ashore child answers parent={} answer={}", parent.getUUID(), memo.reply);
    }

    /**
     * She watches the driven child go. A pleader that steps up to her is turned away: the cold parent shoves it off with the same push as
     * the mould exile, the tearful one turns her back; then she looks after it until it is gone (it is cut at that moment, by the child).
     */
    private static final class Watch implements Intent {
        private final YukkuriEntity parent, child;
        private final Memo memo;
        private final long started;
        private boolean turnedBack;

        Watch(YukkuriEntity parent, YukkuriEntity child, Memo memo) {
            this.parent = parent;
            this.child = child;
            this.memo = memo;
            started = parent.level().getGameTime();
        }

        @Override public String id() { return "watch_leave"; }
        @Override public long deadline() { return started + WATCH; }
        @Override public Commitment commitment() { return child.drivenOut().holding() ? Commitment.HARD : Commitment.SOFT; }
        @Override public boolean satisfied() {
            return !child.isAlive() || !parent.getUUID().equals(child.drivenOut().driver())
                    || child.drivenOut().phase() == DrivenOut.Phase.NONE
                    || !child.drivenOut().holding() && (parent.distanceTo(child) > SIGHT || !parent.hasLineOfSight(child));
        }
        @Override public List<Method> methods() { return List.of(Plan.of("watch_leave", this::derive)); }

        private List<Supplier<Step>> derive() {
            return List.of(() -> new Step.Do(new Face(SurfaceApproach.yaw(parent.position(), child.position()))),
                    () -> new Step.Wait(() -> child.drivenOut().pleaderAtParent()
                            || child.drivenOut().phase() == DrivenOut.Phase.GOING && !child.drivenOut().holding(), PLEA_WAIT, null),
                    this::rebuff,
                    () -> new Step.Wait(() -> {
                        if (!turnedBack) parent.getLookControl().setLookAt(child, 30, 30);
                        return satisfied();
                    }, WATCH, null));
        }

        /** A pleader at her feet is turned away (the child's plea then ends); a child that did not plead needs nothing. */
        private Step rebuff() {
            if (!child.drivenOut().pleaderAtParent()) return new Step.Wait(() -> true, 1, null);
            child.drivenOut().rebuffed();
            if (memo.verdict == Verdict.DRIVE_COLD) {
                // The shove is a beat of her verdict state: LONG, so it replaces the verdict's hold and renews it.
                if (parent.showReaction("angry", "", FaceTier.Tier.LONG)) parent.beat("rebuff_shove", "angry", "");
                GameplayLog.event(parent, "family", "overload rebuffs pleader child={} how=shove", child.getUUID());
                var centre = parent.position();
                return new Step.Do(new Push(child, () -> SHOVE - child.position().subtract(centre).horizontalDistance()));
            }
            turnedBack = true;
            if (parent.showReaction("cry", "", FaceTier.Tier.LONG)) parent.beat("rebuff_turn_back", "cry", "");
            GameplayLog.event(parent, "family", "overload rebuffs pleader child={} how=turn_back", child.getUUID());
            return new Step.Do(new Face(SurfaceApproach.yaw(parent.position(), child.position()) + 180));
        }

        @Override public void ended(IntentRunner.Result result) {
            boolean departed = !parent.getUUID().equals(child.drivenOut().driver());
            if (result == IntentRunner.Result.DONE || departed) MEMOS.remove(parent);
            GameplayLog.event(parent, "family", "overload watch ended child={} result={} departed={}", child.getUUID(), result, departed);
        }
    }
}
