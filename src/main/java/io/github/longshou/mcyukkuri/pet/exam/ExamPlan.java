package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.PetStudy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** The question set is fixed before any prompt, including the care facility selected by the current needs. */
public final class ExamPlan {
    private ExamPlan() {}

    public enum Question {
        NAME(BadgeGrade.BRONZE, PetSkill.GREETING),
        WAIT(BadgeGrade.SILVER, PetSkill.WAIT),
        READ(BadgeGrade.SILVER, PetSkill.READING),
        COUNT(BadgeGrade.SILVER, PetSkill.ARITHMETIC),
        BOWL(BadgeGrade.SILVER, PetSkill.FOOD_BOWL),
        TOILET(BadgeGrade.SILVER, PetSkill.TOILET),
        BED(BadgeGrade.SILVER, PetSkill.BEDTIME),
        WALK(BadgeGrade.GOLD, PetSkill.HARNESS),
        GREET(BadgeGrade.GOLD, PetSkill.GREETING),
        HELP(BadgeGrade.GOLD, null);

        final BadgeGrade grade;
        final PetSkill skill;
        Question(BadgeGrade grade, PetSkill skill) { this.grade = grade; this.skill = skill; }
        String key() { return name().toLowerCase(Locale.ROOT); }
        ExamItem item() { return this == NAME ? new SimpleCheckItem() : new ExamPracticeItem(this); }
    }

    static List<Question> questions(int service, BadgeGrade target, Player owner, YukkuriEntity y) {
        var plan = new ArrayList<Question>();
        if (service == ExamSession.JUDGMENT || target == BadgeGrade.BRONZE) plan.add(Question.NAME);
        if (target == BadgeGrade.SILVER || service == ExamSession.JUDGMENT) {
            plan.addAll(List.of(Question.READ, Question.COUNT, Question.WAIT));
            plan.add(care(y, target == BadgeGrade.SILVER));
        }
        if (target == BadgeGrade.GOLD) plan.addAll(List.of(Question.WALK, Question.GREET, Question.HELP));
        return plan;
    }

    private static Question care(YukkuriEntity y, boolean bedtimeAllowed) {
        var lessons = y.pet().lessons();
        if (lessons.careReady(PetSkill.FOOD_BOWL)) return Question.BOWL;
        if (lessons.careReady(PetSkill.TOILET)) return Question.TOILET;
        if (bedtimeAllowed && lessons.careReady(PetSkill.BEDTIME)) return Question.BED;
        return Question.BOWL;
    }

    static LivingEntity visitor(YukkuriEntity y, Player owner, HumanNpc examiner) {
        return y.level().getEntitiesOfClass(LivingEntity.class, y.getBoundingBox().inflate(6), peer ->
                peer != owner && peer != examiner && peer.isAlive() && y.hasLineOfSight(peer)
                        && (peer instanceof HumanNpc || peer instanceof Player player && !player.isSpectator()))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
    }

    static YukkuriEntity needy(YukkuriEntity y) {
        return y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(5), peer ->
                peer != y && peer.isAlive() && peer.life().nutrition() < 40 && !peer.isPassenger() && !peer.isPanicking()
                        && !peer.pet().busy() && !ExamSession.running(peer) && y.hasLineOfSight(peer))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
    }

    static String refusal(Question question, HumanNpc examiner, Player owner, YukkuriEntity y) {
        return switch (question) {
            case NAME -> null;
            case WAIT -> mouthOccupied(y) ? "occupied"
                    : !y.pet().lessons().preconditionOk(owner, PetSkill.WAIT) ? "need_sweet" : null;
            case READ -> PetStudy.find(y, owner, PetSkill.READING) == null ? "need_reading" : null;
            case COUNT -> {
                var example = PetStudy.find(y, owner, PetSkill.ARITHMETIC);
                yield example == null || example.count() < 1 || example.count() > 4 || !example.source().equals("food")
                        ? "need_count" : null;
            }
            case BOWL, TOILET, BED -> !y.pet().lessons().careReady(question.skill) ? "need_care" : null;
            case WALK -> y.getLeashHolder() != owner || !y.pet().care().harnessed() ? "need_walk" : null;
            case GREET -> visitor(y, owner, examiner) == null ? "no_participant" : null;
            case HELP -> needy(y) == null ? "no_participant" : null;
        };
    }

    private static boolean mouthOccupied(YukkuriEntity y) {
        return y.family().carry().mouthBusy() || !y.family().carrying().isEmpty() || !y.life().mouth().isEmpty();
    }

    static String encode(List<Question> plan) {
        return plan.stream().map(Question::name).collect(Collectors.joining(","));
    }
    static List<Question> decode(String saved) {
        return Arrays.stream(saved.split(",")).map(Question::valueOf).toList();
    }

    static boolean meets(BadgeGrade grade, List<Question> plan, int successes, boolean cumulative) {
        boolean tested = false;
        for (int i = 0; i < plan.size(); i++) {
            var question = plan.get(i);
            if (question.grade == grade) tested = true;
            if ((question.grade == grade || cumulative && question.grade.id() < grade.id()) && (successes & 1 << i) == 0)
                return false;
        }
        return tested;
    }
}
