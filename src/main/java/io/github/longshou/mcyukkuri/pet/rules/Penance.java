package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Set;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The one bound performance of owning a fault in front of the owner, used by every house rule and both entrances: being caught
 * (the pet freezes, stops what it was doing and is startled) and confessing (it walked up and bowed by itself). After the opening
 * the pet lies low and shivers while it waits for the owner's answer: a stroke forgives, a scold or a blow punishes, silence leaves
 * it to withdraw uneasily. Obedience, attachment, fear and boldness pick the posture, the lines and how long it waits. When the
 * answer comes through the pet's own young (the owner scolds or strikes the child, or strokes it), a stroke is the same pardon, and a
 * punishment first makes the parent torn between guarding the child and trusting the owner ({@link StruggleOdds}). Driven by
 * {@link ConscienceGoal}; it owns the pet's face, clip and line from start to end and hands the body back to self-drive.
 */
public final class Penance {
    public enum Entrance { CAUGHT, CONFESSION }
    public enum Verdict { FORGIVEN, PUNISHED }
    private enum Phase { STARTLE, MUTTER, PLEA, EXCUSE, VOW, BOW, WAIT, AFRAID, TORN, ACT, RESOLVE, SETTLE, DONE }

    private static final int STARTLE_TICKS = 24, MUTTER_TICKS = 50, AFRAID_TICKS = 24, RESOLVE_TICKS = 80, CONTACT_PATIENCE = 60;
    /** The posture a pet settles into once the owner has answered: held long enough for its last line to be heard. */
    private static final int SETTLE_TICKS = 80;
    /** A line waits for the shared speech clock (the beat before it may still be speaking) for at most this long, then is dropped and logged. */
    private static final int SPEECH_PATIENCE = 300;
    /** The struggle looks from the child to the owner and back every second. */
    private static final int GLANCE_TICKS = 20;
    /** Stepping in front of the child or running off gives up after four seconds; the beat after the arrival holds one and a half. */
    private static final int ACT_TICKS = 120, ARRIVAL_TICKS = 30;
    /** The parent was not the one struck: a fright of the owner, not the victim's twenty-five. */
    private static final int TORN_FEAR_SCOLD = 8, TORN_FEAR_BLOW = 12;
    /** Farther than this from the owner the pet has lost its audience. */
    static final double AUDIENCE_SQ = 24 * 24;
    /** Eight seconds for the most defiant, fifteen for the most guilty. */
    private static final int MIN_WAIT = 160, WAIT_SPREAD = 140;

    private final YukkuriEntity pet;
    private final LivingEntity owner;
    private final Entrance entrance;
    private final String rule;
    private final Runnable onResolved;
    private final long startedAt;
    private Phase phase;
    private boolean fearful, mutters, guilty;
    private int window, segment;
    private long waitUntil = -1, judgedAt = -1;
    private Verdict verdict;
    private boolean blow, shown, reported, voiced, asked;
    // The child whose treatment was the answer (null when the owner's hand was on the parent), and how the parent ends its struggle.
    private YukkuriEntity child;
    private StruggleOdds.Choice struggle;
    private Vec3 stand, standFor;
    private long settledAt;
    private String outcome = "";
    // The beat on show: its declared clip, face and line, and when it started.
    private String beat = "", face = "", line = "";
    // The line of the beat on show, waiting for the shared speech clock (empty once spoken or dropped), and when the beat's line started.
    private String pending = "";
    private long pendingSince;
    private YukkuriAction clip = YukkuriAction.IDLE;
    private long beatAt;
    private int beatTicks;

    private Penance(YukkuriEntity pet, LivingEntity owner, Entrance entrance, String rule, Runnable onResolved) {
        this.pet = pet;
        this.owner = owner;
        this.entrance = entrance;
        this.rule = rule;
        this.onResolved = onResolved;
        startedAt = now();
        stance();
    }

    /** Caught in the act: startled first, then the posture its stats choose. */
    static Penance caught(YukkuriEntity pet, LivingEntity owner, String rule) {
        var penance = new Penance(pet, owner, Entrance.CAUGHT, rule, () -> {});
        pet.interruptSpeech();
        // The pet registers the owner at once: it freezes, flinches and turns to face them (see tick), and gasps unless it is the defiant kind.
        penance.enter(Phase.STARTLE, "startle", YukkuriAction.STARTLE, "horrified", penance.mutters ? "" : "rule_gasp", STARTLE_TICKS);
        return penance;
    }

    /** Walked up to the owner by itself: bows with {@code openingLine} (see {@link #voiced()}), then lies low and waits. */
    static Penance confession(YukkuriEntity pet, LivingEntity owner, String rule, String openingLine, Runnable onResolved) {
        var penance = new Penance(pet, owner, Entrance.CONFESSION, rule, onResolved);
        penance.enter(Phase.BOW, "bow", YukkuriAction.BOW, "teary", openingLine, YukkuriAction.BOW.durationTicks());
        return penance;
    }

    private void stance() {
        var personality = pet.life().personality();
        var temper = personality.temperament();
        int obedience = pet.pet().obedience(), fear = personality.fear(owner.getUUID());
        fearful = Temperament.ramp(fear, 15, 50) >= .5 || temper.is(Gate.TIMID);
        double guilt = (Temperament.ramp(obedience, 20, 80) + temper.attached()) / 2;
        guilty = guilt >= .5;
        // Only a bold, disobedient pet that does not fear its owner answers a catch with a mutter.
        double defiance = (1 - temper.timid()) * (1 - Temperament.ramp(obedience, 10, 45)) * (1 - Temperament.ramp(fear, 10, 40));
        mutters = entrance == Entrance.CAUGHT && defiance >= .5;
        window = MIN_WAIT + (int) Math.round(WAIT_SPREAD * guilt);
        GameplayLog.event(pet, "rules", "penance begin entrance={} rule={} owner={} posture={} window={} obedience={} fear={} attachment={} boldness={}",
                entrance, rule, owner.getUUID(), mutters ? "mutter" : fearful ? "cower" : guilty ? "guilty" : "wary", window, obedience, fear,
                personality.attachment(), personality.boldness());
    }

    // ---- the owner's answer -------------------------------------------------------------------------

    /** A stroke, a scold or a blow from the owner. True while the penance can still take it, so the generic reaction stays silent. */
    boolean judged(Verdict answer, boolean hit) {
        if (phase == Phase.DONE) return false;
        if (verdict == null) {
            verdict = answer;
            blow = hit;
            judgedAt = now();
            GameplayLog.event(pet, "rules", "penance verdict={} by={} phase={} after={} via={} child={}", answer,
                    answer == Verdict.FORGIVEN ? "stroke" : hit ? "blow" : "scold", phase, now() - startedAt,
                    child == null ? "owner" : "child", child == null ? "-" : child.getUUID());
        }
        return true;
    }

    /** The owner's answer reached the pet through its own young: the same verdict, taken once, remembering whose treatment it was. */
    boolean judgedViaChild(YukkuriEntity young, Verdict answer, boolean hit) {
        if (phase == Phase.DONE || verdict != null) return false;
        child = young;
        return judged(answer, hit);
    }

    /** From the owner's first answer to the end: the owner's own hand on the body is no emergency. */
    boolean ownerHand() { return judgedAt >= 0 && phase != Phase.DONE; }

    boolean finished() { return phase == Phase.DONE; }

    LivingEntity owner() { return owner; }

    String outcome() { return outcome; }

    /** Whether the opening line of a confession was voiced and intelligible. */
    boolean voiced() { return voiced; }

    /** The reason this performance can no longer go on, or null. Its own errands wait; real danger and a lost audience do not. */
    String lapse() {
        if (!owner.isAlive() || owner.level() != pet.level()) return "owner_gone";
        if (!pet.pet().ownedBy(owner.getUUID())) return "owner_changed";
        if (pet.distanceToSqr(owner) > AUDIENCE_SQ) return "owner_away";
        if (pet.isPassenger()) return "mounted";
        if (pet.life().isSleeping()) return "sleeping";
        if (pet.life().hasUrgentNeed()) return "urgent_need";
        if (pet.pet().conscience().endangered()) return "danger";
        return null;
    }

    /** The goal ended without a resolution (danger, the owner left): drop the held face and clip so nothing stale lingers. */
    void abandon(String why) {
        if (phase == Phase.DONE) return;
        GameplayLog.event(pet, "rules", "penance abandoned reason={} phase={} entrance={} rule={}", why, phase, entrance, rule);
        phase = Phase.DONE;
        pet.cancelReaction();
    }

    // ---- the clock ----------------------------------------------------------------------------------

    void tick() {
        if (phase == Phase.DONE) return;
        long now = now();
        if (child != null && (!child.isAlive() || child.body().dying() || child.level() != pet.level())) lostChild();
        // A parent that is stepping in front of the child or running walks; every other beat holds still and faces its focus.
        var focus = focus(now - beatAt);
        pet.getLookControl().setLookAt(focus, 30, 30);
        if (stand == null) {
            SurfaceApproach.halt(pet);
            SurfaceApproach.face(pet, SurfaceApproach.yaw(pet.position(), focus.position()));
        }
        speakPending(now);
        if (!reported && pending.isEmpty() && now - beatAt >= 2) report();
        long age = now - beatAt;
        // The startle is not a choice, and a struggle plays out once begun: neither takes another answer (nor does the settling after the verdict).
        boolean playing = phase == Phase.STARTLE || phase == Phase.AFRAID || phase == Phase.TORN || phase == Phase.ACT || phase == Phase.RESOLVE
                || phase == Phase.SETTLE;
        if (verdict != null && !playing) {
            answer();
            return;
        }
        switch (phase) {
            case STARTLE -> { if (age >= STARTLE_TICKS) { if (verdict != null) answer(); else posture(); } }
            case MUTTER -> { if (spent(now, age)) excuse(); }
            case PLEA -> { if (spent(now, age)) { if (beat.equals("apology")) apologyFollows(); else lieLow(); } }
            case EXCUSE, VOW, BOW -> { if (spent(now, age)) lieLow(); }
            case WAIT -> { if (now >= waitUntil) resolve(); else if (spent(now, age)) lieLow(); }
            case AFRAID -> { if (age >= AFRAID_TICKS) resolve(); }
            case TORN -> { if (age >= beatTicks) act(); }
            case ACT -> acting(now, age);
            case RESOLVE -> resolving(now, age);
            case SETTLE -> { if (spent(now, age)) end(now); }
            default -> {}
        }
        if (phase != Phase.DONE && phase != Phase.RESOLVE && !pet.hasReaction()) show(false);
    }

    /** Whom the head and body turn to: the owner, except while torn (child, owner, child ...) and while the child is being pardoned. */
    private LivingEntity focus(long age) {
        if (child == null) return owner;
        if (phase == Phase.TORN) return age / GLANCE_TICKS % 2 == 0 ? child : owner;
        return phase == Phase.RESOLVE && verdict == Verdict.FORGIVEN ? child : owner;
    }

    /** The child died or went away under the answer: no struggle for it (grief is the body's own), the parent takes what is left of the ending. */
    private void lostChild() {
        GameplayLog.event(pet, "rules", "penance child lost child={} phase={} entrance={} rule={}", child.getUUID(), phase, entrance, rule);
        child = null;
        stand = null;
        if (phase == Phase.TORN || phase == Phase.ACT) {
            SurfaceApproach.halt(pet);
            resolve();
        }
    }

    /** After the startle: the defiant mutter, the afraid one's plea for mercy, or the apology. */
    private void posture() {
        if (mutters) enter(Phase.MUTTER, "mutter", YukkuriAction.BRACE, "squeezed_frown", "sulk_huff", MUTTER_TICKS);
        else if (fearful) enter(Phase.PLEA, "plea", YukkuriAction.THUNDER_COWER, "horrified", "rule_beg", 60);
        else enter(Phase.PLEA, "apology", YukkuriAction.BOW, guilty ? "ashamed" : "look_down", "caught_guilty", YukkuriAction.BOW.durationTicks());
    }

    /** The mutterer gives its reason, still bracing, and apologises small: it is afraid of the owner's answer even if it will not say so. */
    private void excuse() {
        enter(Phase.EXCUSE, "excuse", YukkuriAction.BRACE, "squeezed_frown", "rule_excuse", 50);
    }

    /** The apology goes on: a guilty pet swears never again with its face hidden, a wary one gives its small reason. */
    private void apologyFollows() {
        if (guilty) enter(Phase.VOW, "vow", YukkuriAction.HIDE_FACE, "ashamed", "rule_vow", 50);
        else enter(Phase.EXCUSE, "excuse", YukkuriAction.DROOP, "look_down", "rule_excuse", 50);
    }

    /** Whether the beat on show has run its time and its line (if any) has been said: only then the next beat may start. */
    private boolean spent(long now, long age) {
        return age >= beatTicks && pending.isEmpty() && now >= pet.speechEnd();
    }

    /** The owner answered while the pet still stood stiff: a scold makes it afraid first, then it takes the punishment. */
    private void answer() {
        if (verdict == Verdict.PUNISHED && child != null) torn();
        else if (verdict == Verdict.PUNISHED && mutters && !fearful) {
            pet.interruptSpeech();
            fearful = true;
            enter(Phase.AFRAID, "afraid", YukkuriAction.TREMBLE, "horrified", "", AFRAID_TICKS);
        } else resolve();
    }

    /**
     * The owner punished the pet's own young in front of it: the parent is torn between guarding the child and trusting the owner.
     * Its numbers pick how it ends once, here ({@link StruggleOdds}); the fear it takes is how obedience is taught, not a penalty on the owner.
     */
    private void torn() {
        pet.interruptSpeech();
        var personality = pet.life().personality();
        int obedience = pet.pet().obedience(), fear = personality.fear(owner.getUUID());
        double guilt = (Temperament.ramp(obedience, 20, 80) + personality.temperament().attached()) / 2;
        var scores = StruggleOdds.score(personality.attachment(), personality.boldness(), personality.trust(child.getUUID()), fear,
                obedience, guilt, mobile());
        struggle = scores.choice();
        pet.pet().disciplined(owner.getUUID(), blow, blow ? TORN_FEAR_BLOW : TORN_FEAR_SCOLD);
        int ticks = scores.struggleTicks();
        GameplayLog.event(pet, "rules", "penance torn choice={} child={} guard={} trust={} shield={} plead={} escape={} ticks={} blow={} entrance={} rule={}",
                struggle, child.getUUID(), scores.guard(), scores.trust(), scores.shield(), scores.plead(), scores.escape(), ticks, blow,
                entrance, rule);
        enter(Phase.TORN, "torn", YukkuriAction.TREMBLE, "teary", "", ticks);
    }

    /** A body that can step in front of the child or run: not weak, leashed, off the ground or carrying. */
    private boolean mobile() {
        return pet.onGround() && !pet.isLeashed() && !pet.body().weak() && !pet.family().carry().carrying();
    }

    /** How the struggle ends: in front of the child, begging the owner, bowing to the owner's right, or (rarely) running. */
    private void act() {
        stand = null;
        settledAt = -1;
        switch (struggle) {
            case SHIELD -> {
                enter(Phase.ACT, "shield", YukkuriAction.PUFF, "puff_teary", "protect_taunt", ACT_TICKS);
                if (inFront(pet.position()) && pet.distanceToSqr(child) <= 2.25) settledAt = now();
                else if ((stand = shieldStand()) == null) settledAt = now();
                GameplayLog.event(pet, "rules", "penance step toward=child stand={} child={}", stand != null, child.getUUID());
            }
            case PLEAD -> enter(Phase.ACT, "plead", YukkuriAction.BEG, "teary", "plea_spare", 50);
            case SUBMIT -> enter(Phase.ACT, "submit", YukkuriAction.BOW, "ashamed", "", YukkuriAction.BOW.durationTicks());
            case ESCAPE -> {
                enter(Phase.ACT, "flee", YukkuriAction.FLEE, "horrified", "", ACT_TICKS);
                stand = Reactions.escapeStand(pet, owner, 6, Set.of());
                if (stand == null) settledAt = now();
                GameplayLog.event(pet, "rules", "penance step toward=away stand={} child={}", stand != null, child.getUUID());
            }
        }
    }

    /** On the owner's side of the child, where the owner's hand would meet the parent first. */
    private boolean inFront(Vec3 at) {
        var toOwner = owner.position().subtract(child.position()).multiply(1, 0, 1).normalize();
        var side = at.subtract(child.position()).multiply(1, 0, 1).normalize();
        return side.dot(toOwner) > .8;
    }

    /** A stand on the owner's side of the child where it is now (a scolded child runs, so the stand follows it). */
    private Vec3 shieldStand() {
        standFor = child.position();
        return SurfaceApproach.beside(pet, standFor, (pet.getBbWidth() + child.getBbWidth()) / 2 + .1, child, this::inFront);
    }

    private void acting(long now, long age) {
        if (stand != null && struggle == StruggleOdds.Choice.SHIELD && child.position().distanceToSqr(standFor) > 1) {
            var moved = shieldStand();
            if (moved != null) stand = moved;
        }
        if (stand != null) {
            var result = struggle == StruggleOdds.Choice.ESCAPE ? SurfaceApproach.retreatResult(pet, stand, owner, .9)
                    : SurfaceApproach.walkResult(pet, stand, child, .9);
            if (result == SurfaceApproach.Result.ARRIVED || result == SurfaceApproach.Result.BLOCKED) {
                GameplayLog.event(pet, "rules", "penance step end result={} after={} gap={} toward={}", result, age,
                        pet.distanceTo(struggle == StruggleOdds.Choice.ESCAPE ? owner : child), struggle == StruggleOdds.Choice.ESCAPE ? "owner" : "child");
                stand = null;
                settledAt = now;
            }
        }
        // After the arrival the pose holds a moment, then the owner's answer is taken; a walk that never arrives gives up with the beat.
        if (settledAt >= 0 ? now - settledAt >= ARRIVAL_TICKS : age >= beatTicks) {
            if (stand != null) GameplayLog.event(pet, "rules", "penance step cut after={} toward={}", age,
                    struggle == StruggleOdds.Choice.ESCAPE ? "owner" : "child");
            stand = null;
            resolve();
        }
    }

    /** The whole waiting window, in segments of low posture and shivering (fear is where shaking belongs). */
    private void lieLow() {
        if (waitUntil < 0) waitUntil = now() + window;
        long left = waitUntil - now();
        if (mutters && !fearful) {
            enter(Phase.WAIT, "stiff", YukkuriAction.BRACE, "endure", "", (int) Math.min(60, left));
            return;
        }
        double fright = Temperament.ramp(pet.life().personality().fear(owner.getUUID()), 15, 50);
        String frightened = fearful ? "horrified" : "teary";
        if (segment++ % 2 == 1) {
            // The first shiver asks, small, whether the owner is angry (the stiff defiant one says nothing).
            String asks = asked ? "" : "rule_wait";
            asked = true;
            enter(Phase.WAIT, "shiver", YukkuriAction.TREMBLE, frightened, asks, (int) Math.min(Math.round(24 + 26 * fright), left));
        }
        else enter(Phase.WAIT, "prostrate", fearful ? YukkuriAction.THUNDER_COWER : guilty ? YukkuriAction.HIDE_FACE : YukkuriAction.DROOP,
                frightened, "", (int) Math.min(Math.round(50 - 20 * fright), left));
    }

    /** What the owner did decides the ending; the pet's own state decides how it shows it. */
    private void resolve() {
        if (phase == Phase.RESOLVE) return;
        phase = Phase.RESOLVE;
        shown = false;
        // The ending is only reported once the owner's hand has let go and it is really on show (see resolving).
        reported = true;
        pending = "";
        beatAt = now();
        pet.interruptSpeech();
        onResolved.run();
        double fear = Temperament.ramp(pet.life().personality().fear(owner.getUUID()), 15, 50);
        boolean scared = fearful || fear >= .5;
        if (verdict == Verdict.PUNISHED && child != null) {
            // The child was the one punished: its parent aches for it in silence, the struggle already said what it had to say.
            outcome = blow ? "punished_child_blow" : "punished_child_scold";
            declare("ache", scared ? YukkuriAction.THUNDER_COWER : YukkuriAction.DROOP, scared ? "horrified" : "teary", "");
        } else if (verdict == Verdict.PUNISHED) {
            outcome = blow ? "punished_blow" : "punished_scold";
            declare("punished", scared ? YukkuriAction.THUNDER_COWER : YukkuriAction.DROOP, scared ? "horrified" : blow ? "impact_teary" : "teary",
                    blow && scared ? "hurt_plea" : "scolded");
        } else if (verdict == Verdict.FORGIVEN) {
            outcome = "forgiven";
            declare("forgiven", fear < .5 && !pet.body().weak() ? YukkuriAction.JOY_JUMP : YukkuriAction.DROOP, "closed_smile", "rule_thanks");
            pet.pet().addStress(-6);
        } else {
            outcome = "withdrew";
            GameplayLog.event(pet, "rules", "penance verdict=IGNORED by=none phase=WAIT after={}", now() - startedAt);
            declare("withdraw", YukkuriAction.LOOK_AROUND, "side_teary", "");
            pet.pet().addStress(6);
        }
        beatTicks = RESOLVE_TICKS;
    }

    /** The beat shows once the owner's hand has let go (a stroke is a 40-tick contact, a blow a flinch), so the shown face is the declared one. */
    private void resolving(long now, long age) {
        if (!shown) {
            if ((pet.operations().active() || pet.hurtTime > 0) && age < CONTACT_PATIENCE) return;
            beatAt = now;
            shown = true;
            reported = false;
            show(true);
            return;
        }
        if (age >= RESOLVE_TICKS && pending.isEmpty() && now >= pet.speechEnd()) {
            if (outcome.startsWith("punished_child")) end(now); else settle();
        } else if (!pet.hasReaction()) show(false);
    }

    /**
     * The performance ends in a posture it holds, not in the verdict's pose: the pardoned bows low in thanks, the punished stays bowed and
     * trembling, the ignored one slinks off apologising; a bold, disobedient pet that was not afraid sulks a little. What it says is the
     * owner's judgement taken (a parent that ached for its punished child already said all it had to).
     */
    private void settle() {
        double fear = Temperament.ramp(pet.life().personality().fear(owner.getUUID()), 15, 50);
        boolean sulky = mutters && !fearful && fear < .5;
        switch (outcome) {
            case "forgiven" -> enter(Phase.SETTLE, "settle", sulky ? YukkuriAction.DROOP : YukkuriAction.BOW, sulky ? "pursed" : "closed_smile", "", SETTLE_TICKS);
            case "withdrew" -> enter(Phase.SETTLE, "settle", YukkuriAction.DROOP, "side_teary", "rule_withdraw", SETTLE_TICKS);
            default -> enter(Phase.SETTLE, "settle", sulky ? YukkuriAction.BRACE : fearful ? YukkuriAction.TREMBLE : YukkuriAction.BOW,
                    sulky ? "pursed" : fearful ? "teary" : "ashamed", "rule_accept", SETTLE_TICKS);
        }
    }

    private void end(long now) {
        phase = Phase.DONE;
        GameplayLog.event(pet, "rules", "penance end outcome={} entrance={} rule={} ticks={}", outcome, entrance, rule, now - startedAt);
    }

    // ---- showing a beat -----------------------------------------------------------------------------

    private void declare(String name, YukkuriAction action, String expression, String event) {
        beat = name;
        clip = action;
        face = expression;
        line = event;
    }

    private void enter(Phase next, String name, YukkuriAction action, String expression, String event, int ticks) {
        if (!pending.isEmpty()) {
            GameplayLog.event(pet, "rules", "penance line dropped beat={} line={} waited={}", beat, line, now() - pendingSince);
            pending = "";
        }
        phase = next;
        declare(name, action, expression, event);
        beatAt = now();
        beatTicks = Math.max(1, ticks);
        reported = false;
        show(true);
    }

    private void show(boolean speak) {
        long left = beatAt + beatTicks - now();
        // Two ticks of overlap: the next beat takes over before this one runs out, so no idle face shows between two beats.
        int ticks = speak ? beatTicks + 2 : (int) Math.max(left, 20) + 2;
        pet.holdReaction(clip, face, "", ticks);
        if (!speak) return;
        // The pose comes first; its line is said as soon as the shared speech clock lets this pet speak (the beat before it may still be
        // talking), and the beat is not over until it has been said (spent).
        voiced = false;
        pending = line.isEmpty() ? "" : BadgeSocial.reactionLine(pet, line, owner);
        pendingSince = now();
        speakPending(now());
    }

    private void speakPending(long now) {
        if (pending.isEmpty()) return;
        if (pet.quiet()) {
            boolean said = pet.speakCue(pending, null, 2);
            var heard = NativeDialogue.byKey(pet.getDialogueKey());
            voiced = said && heard != null && heard.events().contains(line);
            pending = "";
        } else if (now - pendingSince >= SPEECH_PATIENCE) {
            GameplayLog.event(pet, "rules", "penance line dropped beat={} line={} waited={}", beat, line, now - pendingSince);
            pending = "";
        }
    }

    /** One line per beat with the face and clip really shown a moment after they were declared. */
    private void report() {
        reported = true;
        GameplayLog.event(pet, "rules", "penance beat={} face={} declared={} line={} spoke={} clip={} declaredClip={} entrance={} rule={} focus={}",
                beat, pet.getExpressionId(), face, line.isEmpty() ? "-" : line, voiced, pet.getAnimationId(), clip.id(), entrance, rule,
                focus(now() - beatAt) == child ? "child" : "owner");
    }

    private long now() { return pet.level().getGameTime(); }
}
