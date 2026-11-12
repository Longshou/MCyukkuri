package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetStudy;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/** Prompts a real routine and observes its existing completion. */
final class ExamPracticeItem implements ExamItem {
    private enum Phase { QUESTION, OBSERVE, COMMENT }
    private final ExamPlan.Question question;
    private Phase phase = Phase.QUESTION;
    private long until;
    private PetStudy.Exercise example;
    private LivingEntity participant;
    private Boolean completed;
    private String observed = "routine";
    private UUID caption;

    ExamPracticeItem(ExamPlan.Question question) { this.question = question; }
    @Override public ExamTaskArea area() { return ExamTaskArea.CARE_ROOM; }
    @Override public BadgeGrade minimumGrade() { return question.grade; }
    @Override public String id() { return question.key(); }
    @Override public boolean passed() { return Boolean.TRUE.equals(completed); }

    @Override public void start(ExamContext ctx) {
        var y = ctx.candidate();
        if (ctx.owner() == null) { ctx.interrupt("owner_gone"); return; }
        var why = ExamPlan.refusal(question, ctx.examiner(), ctx.owner(), y);
        if (why != null) { ctx.interrupt(why); return; }
        switch (question) {
            case READ, COUNT -> example = PetStudy.find(y, ctx.owner(), question.skill);
            case GREET -> participant = ExamPlan.visitor(y, ctx.owner(), ctx.examiner());
            case HELP -> participant = ExamPlan.needy(y);
            default -> { }
        }
        ctx.examinerSay("exam.mcyukkuri.question." + id());
        until = ctx.level().getGameTime() + 60;
    }

    @Override public boolean tick(ExamContext ctx) {
        long now = ctx.level().getGameTime();
        if (phase == Phase.COMMENT) return now >= until;
        if (phase == Phase.QUESTION) {
            if (now < until) return false;
            phase = Phase.OBSERVE;
            beginAnswer(ctx, now);
            return false;
        }
        if (participant != null && (!participant.isAlive() || participant.level() != ctx.level()
                || participant.distanceToSqr(ctx.candidate()) > 64)) {
            ctx.interrupt("no_participant");
            return false;
        }
        if (completed == null && now >= until) {
            if (question == ExamPlan.Question.WALK || question == ExamPlan.Question.GREET
                    || question == ExamPlan.Question.HELP) ctx.interrupt("site");
            else comment(ctx, false, "no_answer");
        } else if (completed != null && now >= until && ctx.inContact()) comment(ctx, completed, observed);
        return false;
    }

    private void beginAnswer(ExamContext ctx, long now) {
        var y = ctx.candidate();
        if (ctx.owner() == null) { ctx.interrupt("owner_gone"); return; }
        if (example != null) {
            var actual = PetStudy.find(y, ctx.owner(), question.skill);
            if (actual == null || !example.sameExample(actual)) { ctx.interrupt("site"); return; }
            var answer = PetStudy.answer(y, actual);
            caption = answer.caption();
            if (caption == null) { ctx.interrupt("site"); return; }
            completed = answer.success();
            observed = example.source();
            until = Math.max(now + answer.ticks(), y.speechEnd());
            return;
        }
        if (question == ExamPlan.Question.HELP) {
            var peer = (YukkuriEntity)participant;
            if (peer.life().nutrition() >= 40 || !y.hasLineOfSight(peer)) { ctx.interrupt("no_participant"); return; }
            var personality = y.life().personality();
            boolean helps = y.getRandom().nextDouble() < personality.helpProbability(false, peer.life().hasUrgentNeed());
            var line = helps ? "offer_help" : "unsure_help";
            ctx.card(y.position().add(0, y.getBbHeight() + .6, 0),
                    Component.translatable("exam.mcyukkuri.reply." + line, peer.getDisplayName()), 100, 1.2f);
            y.showReaction(helps ? "happy" : "confused", helps ? "exam_report" : "exam_unsure", 80);
            if (!helps) { completed = false; until = now + 100; return; }
        }
        ctx.allowRoutine();
        until = now + 420;
        if (question == ExamPlan.Question.WAIT && !y.pet().lessons().examWait(ctx.owner())) ctx.interrupt("need_sweet");
        if (question == ExamPlan.Question.GREET && !y.social().greetForPractice(participant)) ctx.interrupt("no_participant");
        if (question == ExamPlan.Question.BOWL || question == ExamPlan.Question.TOILET || question == ExamPlan.Question.BED)
            y.life().replanNow();
    }

    void performed(ExamContext ctx, boolean success) {
        if (phase == Phase.OBSERVE && completed == null) {
            completed = success;
            until = ctx.level().getGameTime();
        }
    }
    boolean guest(net.minecraft.world.entity.Entity entity) { return participant == entity; }

    void fed(ExamContext ctx, YukkuriEntity receiver, UUID feeder) {
        if (question == ExamPlan.Question.HELP && phase == Phase.OBSERVE && completed == null && receiver == participant
                && (ctx.candidate().getUUID().equals(feeder) || ctx.owner() != null && ctx.owner().getUUID().equals(feeder))) {
            observed = "received_food";
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
    }
}
