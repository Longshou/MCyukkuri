package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.cognition.Ability;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.ExamRecord;
import io.github.longshou.mcyukkuri.pet.PetStudy;
import java.util.ArrayList;
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
        NAME(BadgeGrade.BRONZE, Ability.GREETING),
        WAIT(BadgeGrade.SILVER, Ability.WAIT),
        COUNT(BadgeGrade.SILVER, Ability.COUNTING),
        BOWL(BadgeGrade.SILVER, Ability.FOOD_GUARD),
        TOILET(BadgeGrade.SILVER, Ability.TOILET),
        GREET(BadgeGrade.GOLD, Ability.GREETING),
        HELP(BadgeGrade.GOLD, null),
        CLARIFY(BadgeGrade.PLATINUM, Ability.COUNTING),
        SHARE(BadgeGrade.PLATINUM, null);

        final BadgeGrade grade;
        final Ability skill;
        Question(BadgeGrade grade, Ability skill) { this.grade = grade; this.skill = skill; }
        String key() { return name().toLowerCase(Locale.ROOT); }
        boolean needsHungryPeer() { return this == HELP || this == SHARE; }
        ExamItem item() { return this == NAME ? new SimpleCheckItem() : new ExamPracticeItem(this); }
    }

    static List<Question> questions(int service, BadgeGrade target, YukkuriEntity y) {
        var plan = new ArrayList<Question>();
        if (service == ExamSession.JUDGMENT || target == BadgeGrade.BRONZE) plan.add(Question.NAME);
        if (target == BadgeGrade.SILVER || service == ExamSession.JUDGMENT && target.id() >= BadgeGrade.SILVER.id()) {
            var care = y.pet().lessons().careReady(Ability.TOILET) ? Question.TOILET : Question.BOWL;
            plan.addAll(List.of(Question.COUNT, care, Question.WAIT));
        }
        if (target == BadgeGrade.GOLD) plan.addAll(List.of(Question.GREET, Question.HELP));
        if (target == BadgeGrade.PLATINUM) plan.addAll(List.of(Question.CLARIFY, Question.SHARE));
        return plan;
    }

    static LivingEntity visitor(YukkuriEntity y, Player owner, HumanNpc examiner) {
        return y.level().getEntitiesOfClass(LivingEntity.class, y.getBoundingBox().inflate(6), peer ->
                peer != owner && peer != examiner && peer.isAlive() && y.hasLineOfSight(peer)
                        && !ExamSession.assisting(peer) && (!(peer instanceof HumanNpc human) || !ExamSession.active(human))
                        && (peer instanceof HumanNpc || peer instanceof Player player && !player.isSpectator()))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
    }

    static YukkuriEntity needy(YukkuriEntity y, Question question) {
        return y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(5), peer ->
                peer != y && peer.isAlive() && peer.family().stage() != FamilyLife.Stage.BABY
                        && peer.life().nutrition() < 40 && peer.life().canEat() && !peer.isPassenger() && !peer.isPanicking()
                        && !peer.profile().dangerToYukkuri() && !peer.profile().houseBody()
                        && !peer.isLeashed() && !peer.isVehicle() && !peer.operations().active()
                        && !peer.pet().busy() && !ExamSession.running(peer) && !ExamSession.assisting(peer) && y.hasLineOfSight(peer)
                        && (question != Question.SHARE || !y.dailyLife().sharedFoodToday(peer.getUUID())))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
    }

    static String refusal(Question question, HumanNpc examiner, Player owner, YukkuriEntity y) {
        return switch (question) {
            case NAME -> null;
            case WAIT -> mouthOccupied(y) ? "occupied"
                    : !y.pet().lessons().preconditionOk(owner, Ability.WAIT) ? "need_sweet" : null;
            case COUNT, CLARIFY -> {
                var example = PetStudy.foodCount(y);
                yield example == null || example.count() > 4 ? "need_count" : null;
            }
            case BOWL, TOILET -> !y.pet().lessons().careReady(question.skill) ? "need_care" : null;
            case GREET -> visitor(y, owner, examiner) == null ? "no_participant" : null;
            case HELP, SHARE -> peerRefusal(question, y, needy(y, question));
        };
    }

    static String peerRefusal(Question question, YukkuriEntity y, YukkuriEntity peer) {
        if (peer == null || peer.life().nutrition() >= 40 || !peer.life().canEat() || !y.hasLineOfSight(peer)) return "no_participant";
        if (question == Question.HELP) return null;
        if (mouthOccupied(y)) return "occupied";
        if (y.life().nutrition() < 60) return "share_nutrition";
        return PetStudy.visibleFood(y) < 2 ? "need_count" : null;
    }

    private static boolean mouthOccupied(YukkuriEntity y) {
        return !y.component(YukkuriComponents.MOUTH).freeForExternalWork();
    }

    static String encode(List<Question> plan) {
        return plan.stream().map(Question::name).collect(Collectors.joining(","));
    }
    static Question byName(String name) {
        for (var question : Question.values()) if (question.name().equals(name)) return question;
        return null;
    }

    record Saved(List<Question> questions, int next, int passed, String evidence) {}

    static Saved decode(ExamRecord saved) {
        var names = saved.questions().split(",");
        var questions = new ArrayList<Question>();
        int next = 0, passed = 0;
        for (int i = 0; i < names.length; i++) {
            var question = byName(names[i]);
            if (question == null) continue;
            if (i < saved.next()) next++;
            if ((saved.passed() & 1 << i) != 0) passed |= 1 << questions.size();
            questions.add(question);
        }
        String evidence = saved.evidence();
        if (byName(evidence.split("\\|", 2)[0]) == null) evidence = "";
        return new Saved(questions, next, passed, evidence);
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
