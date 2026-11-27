package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.Duty;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

public final class ConscienceGoal extends Goal {
    private static final double BESIDE_CHILD = 1.5;
    private static final int APPROACH_TICKS = 400;
    /** Walk up, bow, lie low for at most fifteen seconds, take the answer and close: well inside this. */
    private static final int PENANCE_TICKS = 1200;
    private final YukkuriEntity pet;
    private LivingEntity owner;
    private BadgeConduct.Case brood;
    private ArcStyle style;
    private YukkuriEntity child;
    private Vec3 stand;
    private Penance penance;
    /** Caught in the act (the penance starts at once) rather than walking up to confess or answering a secret brood. */
    private boolean caughtStart;
    /** Holding still and facing the owner/target while a rule-change or witness reaction plays (no Duty claimed). */
    private boolean answering;
    private boolean finished, resuming;
    /** Three seconds: how long the owner may look elsewhere while the pet is on its way to them. */
    private static final int GLANCE_AWAY = 60;
    private long unseenSince = -1;
    private String lapseReason;
    /** Whether the one answering the owner bore the brood: only she says "had them in secret"; the father pleads for the little ones. */
    private boolean bearer;

    public ConscienceGoal(YukkuriEntity pet) {
        this.pet = pet;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        var conscience = pet.pet().conscience();
        var waiting = conscience.running();
        if (waiting != null) {
            // The owner's hand took the body (a stroke runs as its own goal) and let go: the performance goes on from where it was.
            String why = !pet.duty().holds(Duty.Kind.RULES) ? "duty_lapsed" : waiting.lapse();
            if (why == null) {
                owner = waiting.owner();
                resuming = true;
                return true;
            }
            waiting.abandon(why);
            conscience.endedPenance();
            pet.duty().release(Duty.Kind.RULES, Duty.End.YIELDED, why);
        }
        conscience.expireCatch();
        answering = false;
        if (!pet.duty().mayTake(Duty.Kind.RULES)) return false;
        caughtStart = false;
        if (conscience.catchPending() && freeToFreeze()) {
            owner = conscience.guiltyOwner();
            if (owner != null && pet.distanceToSqr(owner) <= Penance.AUDIENCE_SQ) {
                brood = null;
                caughtStart = true;
                return true;
            }
        }
        if (conscience.responseDue() && !pet.pet().discovery().open().isEmpty() && pet.pet().isPet()) {
            String why = broodBlock();
            if (why == null) {
                owner = pet.pet().presentOwner();
                if (owner == null) why = "no_owner_present";
                else if (!HumanSight.sees(owner, pet)) why = "owner_not_looking";
                else {
                    for (var entry : pet.pet().discovery().open())
                        if (entry.verdict() == null && wantsResponse(entry) && conscience.mayApproach(entry, owner, child)) { brood = entry; return true; }
                    why = "response_not_ready";
                }
            }
            GameplayLog.limited(pet, "rules", "arc_wait." + why, 200, "arc waiting reason={}", why);
        }
        if (conscience.undiscovered() && conscience.confesses()) {
            owner = conscience.encounter();
            if (owner != null) return true;
        }
        if (conscience.answeringRule()) {
            owner = conscience.answerFocus();
            answering = true;
            return true;
        }
        return false;
    }

    /** A catch freezes the pet where it stands, but not mid-meal, mid-carry or in a flight. */
    private boolean freeToFreeze() {
        return !pet.isPassenger() && !pet.life().isSleeping() && pet.life().mealTicks() == 0 && !pet.pet().busy()
                && !pet.family().carry().carrying() && pet.hurtTime == 0 && !BehaviorContinuity.emergency(pet);
    }

    /** Why the pet cannot answer its secret brood just now (null when it can): the first of the body's own commitments. */
    private String broodBlock() {
        if (BehaviorContinuity.emergency(pet)) return "emergency";
        if (pet.hurtTime != 0) return "hurt";
        if (pet.isPassenger()) return "passenger";
        if (pet.isLeashed()) return "leashed";
        if (pet.pet().busy()) return "pet_busy";
        if (pet.life().protectsStep()) return "protects_step";
        if (pet.life().hasUrgentNeed()) return "urgent_need";
        if (pet.life().isSleeping()) return "sleeping";
        if (pet.house().active()) return "house";
        if (pet.family().carry().carrying()) return "carrying";
        if (!pet.family().yieldsToComfort()) return "comfort_task";
        return pet.family().hasUrgentlyHungryChild() ? "hungry_child" : null;
    }

    private boolean wantsResponse(BadgeConduct.Case entry) {
        var level = (ServerLevel) pet.level();
        var family = FamilyData.get(level).brood(entry.brood);
        boolean mother = pet.getUUID().equals(family.mother);
        bearer = mother;
        style = entry.parents.get(mother ? 0 : 1).style();
        if (style == null) return false;
        child = null;
        boolean born = false;
        for (var member : family.children) {
            if (!member.born) continue;
            born = true;
            if (!(level.getEntity(member.id) instanceof YukkuriEntity young) || !young.isAlive()) {
                if (style == ArcStyle.HIDE) return false;
                continue;
            }
            if (!young.childhood().shouldStayWithParents()) continue;
            if (!young.childhood().firstMealDone() || young.life().nutrition() < 30 || young.isPanicking()) return false;
            if (style == ArcStyle.HIDE && (ParentCarry.needsCarry(young) || young.isPassenger()
                    || young.life().isSleeping() || pet.distanceToSqr(young) > 36)) return false;
            if (child == null || pet.distanceToSqr(young) < pet.distanceToSqr(child)) child = young;
        }
        if (!born) return mother && pet.pet().discovery().leavesNest(pet, family);
        return switch (style) {
            case CONFESS -> true;
            case DEFIANT -> pet.distanceToSqr(owner) <= 16;
            case LIE -> child != null && pet.distanceToSqr(child) <= 16;
            case NAIVE -> child != null && pet.distanceToSqr(owner) <= 144;
            case HIDE -> child != null;
        };
    }

    @Override public void start() {
        var conscience = pet.pet().conscience();
        finished = false;
        lapseReason = null;
        unseenSince = -1;
        if (resuming) {
            resuming = false;
            penance = conscience.running();
            return;
        }
        if (answering) {
            SurfaceApproach.halt(pet);
            return;
        }
        conscience.clearAnswerHold();
        boolean long_ = caughtStart || brood == null || style == ArcStyle.CONFESS;
        pet.duty().take(Duty.Kind.RULES, long_ ? PENANCE_TICKS : APPROACH_TICKS, 600, caughtStart ? "caught" : brood == null ? "confess" : style.name());
        if (caughtStart) {
            // Stop what it was doing (the song ends at once) and freeze: the penance owns the face, the clip and the line from here.
            pet.dailyLife().stop();
            begin(Penance.caught(pet, owner, conscience.guiltRule()));
            return;
        }
        if (brood == null) return;
        if (style == ArcStyle.LIE || style == ArcStyle.CONFESS || style == ArcStyle.NAIVE)
            pet.pet().discovery().beginExchange(pet, brood, APPROACH_TICKS);
        switch (style) {
            case HIDE -> {
                // Join a child that already has cover. A mobile child in the open can follow a short retreat.
                stand = child != null && !GlassSight.clearTo(owner, child.getEyePosition())
                        ? SurfaceApproach.beside(pet, child, 1, .5, BESIDE_CHILD, this::screened)
                        : Reactions.escapeStand(pet, owner, 6, at -> screened(at) && (child == null || child.distanceToSqr(at) <= 36));
                String hideLine = pet.life().personality().intelligence() < 45 ? "exam_hide_fail" : "sukkiri_hide";
                PetScenes.act(pet, YukkuriAction.SHY, "confused_small", hideLine, 40, owner);
                if (stand == null) finish(Duty.End.FAILED, "no_cover");
            }
            case LIE -> {
                var away = pet.position().scale(2).subtract(child.position());
                pet.getLookControl().setLookAt(away.x, pet.getEyeY(), away.z);
                if (say(YukkuriAction.TURN, "confused_small", "sukkiri_lie", null)) {
                    stand = Reactions.escapeStand(pet, child, 3, Set.of());
                }
                if (stand == null) finish(Duty.End.DONE, "denial_ended");
            }
            case DEFIANT -> {
                if (PetScenes.ready(pet, "brood_defiance", 1200)) say(YukkuriAction.PUFF, "cheek_puff", "sukkiri_defiant", owner);
                finish(Duty.End.DONE, "confronted");
            }
            default -> {}
        }
    }

    private boolean screened(Vec3 at) { return !GlassSight.clearTo(owner, at.add(0, pet.getEyeHeight(), 0)); }

    private boolean reachedCover() {
        return screened(pet.position()) && (child == null || pet.distanceToSqr(child) <= BESIDE_CHILD * BESIDE_CHILD
                && pet.hasLineOfSight(child) && !GlassSight.clearTo(owner, child.getEyePosition()));
    }

    @Override public boolean canContinueToUse() { return (lapseReason = lapse()) == null; }

    /** The condition that ends the goal (logged by {@link #stop}), or null while it goes on. */
    private String lapse() {
        if (answering) return pet.pet().conscience().answeringRule() ? null : "answer_ended";
        if (!pet.duty().holds(Duty.Kind.RULES)) return "duty_released";
        if (penance != null) return penance.lapse();
        if (brood != null) {
            if (brood.verdict() != null) return "verdict_given";
            String blocked = broodBlock();
            if (blocked != null) return "not_free:" + blocked;
            if (!pet.pet().ownedBy(owner.getUUID())) return "owner_changed";
            if (!owner.isAlive() || owner.level() != pet.level()) return "owner_gone";
            if (pet.distanceToSqr(owner) > 256) return "owner_away";
            if (style != ArcStyle.HIDE && !watched()) return "owner_not_looking";
            if (child != null && (!child.isAlive() || child.level() != pet.level())) return "child_gone";
            if (child != null && (child.life().nutrition() < 30 || child.isPanicking())) return "child_in_need";
            return null;
        }
        if (!pet.pet().conscience().undiscovered()) return "found_out";
        if (!pet.pet().canWelcomeOwner()) return "not_calm";
        if (pet.distanceToSqr(owner) > 36) return "owner_far";
        if (!watched()) return "owner_not_looking";
        return pet.hasLineOfSight(owner) ? null : "no_line";
    }

    /** The owner looks at the pet, or looked away only a moment ago (a player turning the camera does not cancel the walk up to them). */
    private boolean watched() {
        long now = pet.level().getGameTime();
        if (HumanSight.sees(owner, pet)) {
            unseenSince = -1;
            return true;
        }
        if (unseenSince < 0) unseenSince = now;
        return now - unseenSince < GLANCE_AWAY;
    }

    private void begin(Penance running) {
        penance = running;
        pet.pet().conscience().startedPenance(running);
    }

    @Override public void tick() {
        if (answering) {
            var focus = pet.pet().conscience().answerFocus();
            if (focus != null && focus.isAlive()) {
                SurfaceApproach.halt(pet);
                SurfaceApproach.face(pet, SurfaceApproach.yaw(pet.position(), focus.position()));
                pet.getLookControl().setLookAt(focus, 30, 30);
            }
            return;
        }
        if (!pet.duty().holds(Duty.Kind.RULES)) return;
        if (penance != null) {
            penance.tick();
            if (penance.finished()) finish(Duty.End.DONE, penance.outcome());
            return;
        }
        if (brood != null && (style == ArcStyle.HIDE || style == ArcStyle.LIE)) {
            boolean sheltered = style == ArcStyle.HIDE && reachedCover();
            var result = sheltered ? SurfaceApproach.Result.ARRIVED
                    : SurfaceApproach.retreatResult(pet, stand, style == ArcStyle.HIDE ? owner : child, .9);
            if (result == SurfaceApproach.Result.ARRIVED) {
                if (style == ArcStyle.HIDE && !sheltered) {
                    finish(Duty.End.FAILED, "cover_lost");
                    return;
                }
                GameplayLog.event(pet, "rules", "arc arrived style={} brood={} screened={}", style, brood.brood,
                        !GlassSight.clearTo(owner, pet.getEyePosition()));
                finish(Duty.End.DONE, "arrived");
            } else if (result == SurfaceApproach.Result.BLOCKED) finish(Duty.End.FAILED, "unreachable");
            return;
        }
        if (pet.distanceToSqr(owner) <= 2.25) {
            var conscience = pet.pet().conscience();
            if (brood == null) {
                conscience.caught("confession");
                begin(Penance.confession(pet, owner, conscience.guiltRule(), "rule_confess", () -> {}));
                return;
            }
            if (style == ArcStyle.CONFESS) {
                SurfaceApproach.halt(pet);
                // The opening line must be sayable now: a mother that spoke a moment ago (greeting the owner on the way) stands
                // facing them until the speech gap is over, instead of giving the confession up. The goal's own lapse rules still end it.
                long free = pet.nextSpeech(2);
                if (free > pet.level().getGameTime()) {
                    pet.getLookControl().setLookAt(owner, 30, 30);
                    GameplayLog.limited(pet, "rules", "arc_wait.speech_gap", 100, "arc waiting reason=speech_gap until={} owner={}", free, owner.getUUID());
                    return;
                }
                String event = bearer ? "confess" : "confess_mate";
                var opening = Penance.confession(pet, owner, "secret_brood", event, () -> pet.pet().discovery().admitted(pet, owner));
                if (opening.voiced()) {
                    spoke(event);
                    begin(opening);
                    return;
                }
                opening.abandon("unvoiced");
            } else say(YukkuriAction.JOY_JUMP, "happy", "sukkiri_naive", child);
            finish(Duty.End.DONE, "met_owner");
        } else {
            var stand = SurfaceApproach.beside(pet, owner, 1, .5, 1.5);
            if (stand == null || SurfaceApproach.walkResult(pet, stand, owner, .8) == SurfaceApproach.Result.BLOCKED)
                finish(Duty.End.FAILED, "unreachable");
        }
    }

    private boolean say(YukkuriAction action, String face, String event, LivingEntity look) {
        if (!PetScenes.say(pet, action, face, event, look)) return false;
        spoke(event);
        return true;
    }

    private void spoke(String event) {
        GameplayLog.event(pet, "rules", "arc spoke={} brood={} owner={}", event, brood.brood, owner.getUUID());
        if (style != ArcStyle.DEFIANT)
            pet.pet().discovery().spoke(pet, brood, style, owner, style == ArcStyle.NAIVE ? child.getUUID() : null);
    }

    private void finish(Duty.End end, String reason) {
        finished = true;
        var conscience = pet.pet().conscience();
        if (brood != null) conscience.approachEnded(brood, owner, child);
        conscience.reacted();
        pet.duty().release(Duty.Kind.RULES, end, reason);
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void stop() {
        if (answering) {
            answering = false;
            owner = null;
            SurfaceApproach.stopWalking(pet);
            return;
        }
        var conscience = pet.pet().conscience();
        if (!finished && penance != null && penance.ownerHand() && lapseReason == null) {
            // Taken by the owner's own hand: the performance, the duty and the brood exchange stay as they are until it lets go.
            GameplayLog.event(pet, "rules", "conscience goal suspended for the owner's hand mode={} owner={}",
                    caughtStart ? "caught" : brood == null ? "confess" : style, owner.getUUID());
            penance = null;
            SurfaceApproach.stopWalking(pet);
            return;
        }
        String why = finished ? "finished" : lapseReason != null ? lapseReason : "preempted";
        if (!finished) {
            // An end that was not the goal's own: say which continue-condition (or which higher goal) took it, and change what
            // the next start sees so the same encounter is not retried unchanged.
            GameplayLog.event(pet, "rules", "conscience goal ended reason={} mode={} owner={}", why,
                    caughtStart ? "caught" : brood == null ? "confess" : style, owner == null ? null : owner.getUUID());
            if (penance != null) penance.abandon(why);
            if (brood != null) conscience.approachEnded(brood, owner, child);
        }
        if (brood != null) pet.pet().discovery().endApproach(pet);
        conscience.endedPenance();
        penance = null;
        brood = null;
        stand = null;
        child = null;
        caughtStart = false;
        SurfaceApproach.stopWalking(pet);
        pet.duty().release(Duty.Kind.RULES, Duty.End.YIELDED, why);
    }
}
