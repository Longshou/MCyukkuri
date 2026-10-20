package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.exam.items.*;
import java.util.ArrayList;
import java.util.List;

/** Concrete tasks required for each badge service. Badge decisions use task completion, never four derived subject scores. */
public final class ExamPlan {
    private ExamPlan() {}
    public record Result(String task, BadgeGrade minimumGrade, boolean passed, String failureReason) {}

    /** Whether the hall can stage every physical task required for the requested level. */
    public static boolean supports(ExamHall hall, BadgeGrade target) {
        if (hall == null || !hall.supports(ExamTaskArea.CARE_ROOM)) return false;
        if (target.id() >= BadgeGrade.SILVER.id() && !hall.supports(ExamTaskArea.PODIUM)) return false;
        return target != BadgeGrade.GOLD || hall.supports(ExamTaskArea.CHALLENGE);
    }

    /** The questions in order, omitting a task when the hall cannot stage its physical area. */
    public static List<ExamItem> items(int type, BadgeGrade target, ExamHall hall) {
        var out = new ArrayList<ExamItem>();
        if (type == 3) { out.add(new SimpleCheckItem()); return out; }
        if (hall == null) return out;
        addIf(out, new SocialFeedItem(), hall, target);
        addIf(out, new SocialToiletItem(), hall, target);
        addIf(out, new SocialSleepItem(), hall, target);
        addIf(out, new SocialFightItem(), hall, target);
        addIf(out, new OralSumItem(), hall, target);
        addIf(out, new OralReadItem(), hall, target);
        addIf(out, new WitsHideItem(), hall, target);
        addIf(out, new WitsPromiseItem(), hall, target);
        addIf(out, new WitsInterviewItem(), hall, target);
        addIf(out, new PoiseWildItem(), hall, target);
        addIf(out, new PoiseTroubleItem(), hall, target);
        return out;
    }
    private static void addIf(List<ExamItem> out, ExamItem item, ExamHall hall, BadgeGrade target) {
        if (item.minimumGrade().id() <= target.id() && hall.supports(item.area())) out.add(item);
    }
    /** A level is earned only when every concrete task required at that level completed. */
    public static boolean meets(BadgeGrade grade, List<Result> results) {
        if (grade == BadgeGrade.NONE) return true;
        boolean required = false;
        for (var result : results) if (result.minimumGrade().id() <= grade.id()) {
            required = true;
            if (!result.passed()) return false;
        }
        return required;
    }
}
