package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.entity.LivingEntity;

/**
 * The one bound performance of owning a fault in front of the owner, used by every house rule and both entrances: being caught
 * (the pet freezes, stops what it was doing and is startled) and confessing (it walked up and bowed by itself). After the opening
 * the pet lies low and shivers while it waits for the owner's answer: a stroke forgives, a scold or a blow punishes, silence leaves
 * it to withdraw uneasily. Obedience, attachment, fear and boldness pick the posture, the lines and how long it waits. Driven by
 * {@link ConscienceGoal}; it owns the pet's face, clip and line from start to end and hands the body back to self-drive.
 */
public final class Penance {
    public enum Entrance { CAUGHT, CONFESSION }
    public enum Verdict { FORGIVEN, PUNISHED }
    private enum Phase { STARTLE, MUTTER, PLEA, BOW, WAIT, AFRAID, RESOLVE, DONE }

    private static final int STARTLE_TICKS = 14, MUTTER_TICKS = 40, AFRAID_TICKS = 24, RESOLVE_TICKS = 64, CONTACT_PATIENCE = 60;
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
    private boolean blow, shown, reported, voiced;
    private String outcome = "";
    // The beat on show: its declared clip, face and line, and when it started.
    private String beat = "", face = "", line = "";
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
        penance.enter(Phase.STARTLE, "startle", YukkuriAction.STARTLE, "horrified", "", STARTLE_TICKS);
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
            GameplayLog.event(pet, "rules", "penance verdict={} by={} phase={} after={}", answer,
                    answer == Verdict.FORGIVEN ? "stroke" : hit ? "blow" : "scold", phase, now() - startedAt);
        }
        return true;
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
        pet.getLookControl().setLookAt(owner, 30, 30);
        SurfaceApproach.halt(pet);
        SurfaceApproach.face(pet, SurfaceApproach.yaw(pet.position(), owner.position()));
        if (!reported && now - beatAt >= 2) report();
        long age = now - beatAt;
        // The startle is not a choice: it plays out before any answer is taken.
        if (verdict != null && phase != Phase.STARTLE && phase != Phase.AFRAID && phase != Phase.RESOLVE) {
            answer();
            return;
        }
        switch (phase) {
            case STARTLE -> { if (age >= STARTLE_TICKS) { if (verdict != null) answer(); else posture(); } }
            case MUTTER, PLEA, BOW -> { if (age >= beatTicks) lieLow(); }
            case WAIT -> { if (now >= waitUntil) resolve(); else if (age >= beatTicks) lieLow(); }
            case AFRAID -> { if (age >= AFRAID_TICKS) resolve(); }
            case RESOLVE -> resolving(now, age);
            default -> {}
        }
        if (phase != Phase.DONE && phase != Phase.RESOLVE && !pet.hasReaction()) show(false);
    }

    private void posture() {
        if (mutters) enter(Phase.MUTTER, "mutter", YukkuriAction.BRACE, "squeezed_frown", "sulk_huff", MUTTER_TICKS);
        else if (fearful) enter(Phase.PLEA, "plea", YukkuriAction.THUNDER_COWER, "horrified", "plea_spare", 40);
        else enter(Phase.PLEA, "apology", YukkuriAction.BOW, guilty ? "ashamed" : "look_down", "caught_guilty", YukkuriAction.BOW.durationTicks());
    }

    /** The owner answered while the pet still stood stiff: a scold makes it afraid first, then it takes the punishment. */
    private void answer() {
        if (verdict == Verdict.PUNISHED && mutters && !fearful) {
            pet.interruptSpeech();
            fearful = true;
            enter(Phase.AFRAID, "afraid", YukkuriAction.TREMBLE, "horrified", "", AFRAID_TICKS);
        } else resolve();
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
        if (segment++ % 2 == 1) enter(Phase.WAIT, "shiver", YukkuriAction.TREMBLE, frightened, "", (int) Math.min(Math.round(24 + 26 * fright), left));
        else enter(Phase.WAIT, "prostrate", fearful ? YukkuriAction.THUNDER_COWER : guilty ? YukkuriAction.HIDE_FACE : YukkuriAction.DROOP,
                frightened, "", (int) Math.min(Math.round(50 - 20 * fright), left));
    }

    /** What the owner did decides the ending; the pet's own state decides how it shows it. */
    private void resolve() {
        if (phase == Phase.RESOLVE) return;
        phase = Phase.RESOLVE;
        shown = false;
        beatAt = now();
        pet.interruptSpeech();
        onResolved.run();
        double fear = Temperament.ramp(pet.life().personality().fear(owner.getUUID()), 15, 50);
        boolean scared = fearful || fear >= .5;
        if (verdict == Verdict.PUNISHED) {
            outcome = blow ? "punished_blow" : "punished_scold";
            declare("punished", scared ? YukkuriAction.THUNDER_COWER : YukkuriAction.DROOP, scared ? "horrified" : blow ? "impact_teary" : "teary",
                    blow && scared ? "hurt_plea" : "scolded");
        } else if (verdict == Verdict.FORGIVEN) {
            outcome = "forgiven";
            declare("forgiven", fear < .5 && !pet.body().weak() ? YukkuriAction.JOY_JUMP : YukkuriAction.DROOP, "closed_smile", "request_thanks");
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
        if (age >= RESOLVE_TICKS) {
            phase = Phase.DONE;
            GameplayLog.event(pet, "rules", "penance end outcome={} entrance={} rule={} ticks={}", outcome, entrance, rule, now - startedAt);
        } else if (!pet.hasReaction()) show(false);
    }

    // ---- showing a beat -----------------------------------------------------------------------------

    private void declare(String name, YukkuriAction action, String expression, String event) {
        beat = name;
        clip = action;
        face = expression;
        line = event;
    }

    private void enter(Phase next, String name, YukkuriAction action, String expression, String event, int ticks) {
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
        boolean said = pet.holdReaction(clip, face, speak && !line.isEmpty() ? BadgeSocial.reactionLine(pet, line, owner) : "", ticks);
        if (speak) {
            var heard = NativeDialogue.byKey(pet.getDialogueKey());
            voiced = said && heard != null && heard.events().contains(line);
        }
    }

    /** One line per beat with the face and clip really shown a moment after they were declared. */
    private void report() {
        reported = true;
        GameplayLog.event(pet, "rules", "penance beat={} face={} declared={} line={} spoke={} clip={} declaredClip={} entrance={} rule={}",
                beat, pet.getExpressionId(), face, line.isEmpty() ? "-" : line, voiced, pet.getAnimationId(), clip.id(), entrance, rule);
    }

    private long now() { return pet.level().getGameTime(); }
}
