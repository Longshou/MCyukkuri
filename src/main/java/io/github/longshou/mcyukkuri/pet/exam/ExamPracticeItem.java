package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetConversation;
import io.github.longshou.mcyukkuri.pet.PetStudy;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Prompts a real routine and observes its existing completion. */
final class ExamPracticeItem implements ExamItem {
    private enum Phase { QUESTION, COUNT_GROUPS, CLARIFY, OBSERVE, COMMENT }
    private final ExamPlan.Question question;
    private Phase phase = Phase.QUESTION;
    private long until;
    private PetStudy.Exercise example;
    private LivingEntity participant;
    private Boolean completed;
    private String observed = "routine";
    private UUID caption;
    private String foodIncident;
    private boolean asksOwner;

    ExamPracticeItem(ExamPlan.Question question) { this.question = question; }
    @Override public String id() { return question.key(); }
    @Override public boolean passed() { return Boolean.TRUE.equals(completed); }

    @Override public void start(ExamContext ctx) {
        var y = ctx.candidate();
        if (ctx.owner() == null) { ctx.interrupt("owner_gone"); return; }
        var why = question.needsHungryPeer() ? ExamPlan.peerRefusal(question, y, ctx.hungryPeer())
                : ExamPlan.refusal(question, ctx.examiner(), ctx.owner(), y);
        if (why != null) { ctx.interrupt(why); return; }
        switch (question) {
            case READ, COUNT, EXPLAIN, CLARIFY -> example = ExamPlan.example(question, ctx.owner(), y);
            case GREET -> participant = ExamPlan.visitor(y, ctx.owner(), ctx.examiner());
            case HELP, SHARE -> participant = ctx.hungryPeer();
            default -> { }
        }
        ctx.examinerSay("exam.mcyukkuri.question." + id());
        until = ctx.level().getGameTime() + 60;
    }

    @Override public boolean tick(ExamContext ctx) {
        long now = ctx.level().getGameTime();
        if (phase == Phase.COMMENT) return now >= until;
        if (participant instanceof YukkuriEntity peer && PetConversation.sharingDanger(ctx.candidate(), peer)) {
            PetConversation.callOwnerForDanger(ctx.candidate(), peer, ctx.owner());
            ctx.interrupt("danger");
            return false;
        }
        if (!question.needsHungryPeer() && participant != null && (!participant.isAlive() || participant.level() != ctx.level()
                || participant.distanceToSqr(ctx.candidate()) > 64
                || participant instanceof HumanNpc human && ExamSession.active(human)
                || participant instanceof YukkuriEntity peer && ExamSession.running(peer))) {
            ctx.interrupt("no_participant");
            return false;
        }
        if (phase == Phase.QUESTION) {
            if (now < until || !ctx.inContact()) return false;
            if (question == ExamPlan.Question.CLARIFY) {
                var y = ctx.candidate();
                y.getLookControl().setLookAt(ctx.examiner());
                int groups = PetStudy.visibleFoodStacks(y).size();
                if (groups == 0) { ctx.interrupt("site"); return false; }
                ctx.card(y.position().add(0, y.getBbHeight() + .6, 0),
                        Component.translatable("exam.mcyukkuri.reply.groups", groups), 60, 1.2f);
                y.showReaction("neutral", "exam_answer", 60);
                ctx.answer("clarify_groups", true, Integer.toString(groups));
                phase = Phase.COUNT_GROUPS;
                until = Math.max(now + 60, y.speechEnd());
            } else {
                phase = Phase.OBSERVE;
                beginAnswer(ctx, now);
            }
            return false;
        }
        if (phase == Phase.COUNT_GROUPS || phase == Phase.CLARIFY) {
            if (now < until || !ctx.inContact()) return false;
            if (phase == Phase.COUNT_GROUPS) {
                ctx.examinerSay("exam.mcyukkuri.say.count_portions");
                phase = Phase.CLARIFY;
                until = now + 60;
            } else {
                phase = Phase.OBSERVE;
                beginAnswer(ctx, now);
            }
            return false;
        }
        if (completed == null && now >= until) {
            if (question == ExamPlan.Question.WALK || question == ExamPlan.Question.GREET) ctx.interrupt("site");
            else if (ctx.inContact()) comment(ctx, false, "no_answer");
        } else if (completed != null && now >= until && ctx.inContact()) comment(ctx, completed, observed);
        return false;
    }

    private void beginAnswer(ExamContext ctx, long now) {
        var y = ctx.candidate();
        if (ctx.owner() == null) { ctx.interrupt("owner_gone"); return; }
        if (example != null) {
            var actual = ExamPlan.example(question, ctx.owner(), y);
            if (actual == null || !example.sameExample(actual)) { ctx.interrupt("site"); return; }
            y.getLookControl().setLookAt(ctx.examiner());
            boolean detailed = question.grade == BadgeGrade.PLATINUM && y.getRandom().nextDouble() < PetConversation.explanation(y);
            var answer = PetStudy.answer(y, actual, detailed);
            caption = answer.caption();
            if (caption == null) { ctx.interrupt("site"); return; }
            completed = answer.success() && (question.grade != BadgeGrade.PLATINUM || detailed);
            observed = example.source();
            until = Math.max(now + answer.ticks(), y.speechEnd());
            return;
        }
        if (question.needsHungryPeer()) {
            var peer = (YukkuriEntity)participant;
            if (peer.life().nutrition() >= 40 || !y.hasLineOfSight(peer)) { ctx.interrupt("no_participant"); return; }
            var personality = y.life().personality();
            double willingness = question == ExamPlan.Question.SHARE ? PetConversation.considerate(y)
                    : personality.helpProbability(personality.trust(peer.getUUID()), peer.life().hasUrgentNeed());
            boolean helps = y.getRandom().nextDouble() < willingness;
            asksOwner = helps && (question == ExamPlan.Question.HELP || !y.life().canEat() || !ItemContact.mouthsMeet(y, peer));
            var line = !helps ? "unsure_help" : asksOwner ? "offer_help" : "offer_share";
            ctx.card(y.position().add(0, y.getBbHeight() + .6, 0),
                    Component.translatable("exam.mcyukkuri.reply." + line, peer.getDisplayName()), 100, 1.2f);
            y.showReaction(helps ? "happy" : "confused", helps ? "exam_report" : "exam_unsure", 80);
            if (!helps) { completed = false; until = now + 100; return; }
            foodIncident = AskLedger.server().open(y, "food_help", "peer=" + peer.getUUID());
        }
        ctx.allowRoutine();
        until = now + 420;
        if (question == ExamPlan.Question.WAIT && !y.pet().lessons().examWait(ctx.owner())) ctx.interrupt("need_sweet");
        if (question == ExamPlan.Question.GREET && !y.social().greetForPractice(participant)) ctx.interrupt("no_participant");
        if (question == ExamPlan.Question.BOWL || question == ExamPlan.Question.TOILET || question == ExamPlan.Question.BED)
            y.life().replanNow();
        if (question == ExamPlan.Question.SHARE) y.life().replanNow();
    }

    void performed(ExamContext ctx, boolean success) {
        if (phase == Phase.OBSERVE && completed == null) {
            completed = success;
            until = ctx.level().getGameTime();
        }
    }
    boolean guest(Entity entity) { return participant == entity; }
    YukkuriEntity sharingTarget() {
        return question == ExamPlan.Question.SHARE && phase == Phase.OBSERVE && completed == null
                && foodIncident != null && !asksOwner && participant instanceof YukkuriEntity peer ? peer : null;
    }
    boolean allowsHelpFeeder(ExamContext ctx, UUID feeder) {
        if (!question.needsHungryPeer() || phase != Phase.OBSERVE || foodIncident == null) return false;
        return asksOwner ? ctx.owner() != null && ctx.owner().getUUID().equals(feeder) : ctx.candidate().getUUID().equals(feeder);
    }

    void fed(ExamContext ctx, YukkuriEntity receiver, UUID feeder) {
        var donor = ctx.candidate();
        boolean assisted = asksOwner && ctx.owner() != null && ctx.owner().getUUID().equals(feeder);
        if (foodIncident != null && phase == Phase.OBSERVE && completed == null && receiver == participant
                && donor.hasLineOfSight(receiver) && !PetConversation.sharingDanger(donor, receiver)
                && (donor.getUUID().equals(feeder) || assisted)) {
            if (question == ExamPlan.Question.SHARE && assisted) {
                if (donor.life().nutrition() < 60 || donor.dailyLife().sharedFoodToday(receiver.getUUID())) return;
                donor.dailyLife().sharedFoodWith(receiver.getUUID());
            }
            AskLedger.server().ended(foodIncident, receiver.getUUID(), "fed");
            AskLedger.server().settle(foodIncident, "fed");
            foodIncident = null;
            observed = assisted ? "received_owner_food" : "received_food";
            completed = true;
            until = ctx.level().getGameTime();
        }
    }

    private void comment(ExamContext ctx, boolean success, String observed) {
        completed = success;
        phase = Phase.COMMENT;
        ctx.answer(id(), success, observed);
        ctx.note("exam.mcyukkuri.comment." + id() + (success ? "_ok" : "_fail"));
        ctx.examinerSay("exam.mcyukkuri.comment." + id() + (success ? "_ok" : "_fail"));
        if (success) ctx.examinerSwing();
        until = ctx.level().getGameTime() + 60;
    }

    @Override public void abort(ExamContext ctx) {
        Staging.removeCard(ctx.level(), caption);
        if (question == ExamPlan.Question.WAIT) ctx.candidate().pet().lessons().stopExamWait();
        if (foodIncident != null) AskLedger.server().settle(foodIncident, "ended");
    }
}
