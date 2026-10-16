package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.exam.items.*;
import java.util.ArrayList;
import java.util.List;

/** Which questions each exam type asks. Types use the ExamRecord codes: 3 simple, 4 judgment, 5 promotion, 6 renewal. */
public final class ExamPlan {
    private ExamPlan() {}
    public static final List<Ability> ALL = List.of(Ability.MANNERS, Ability.ACADEMICS, Ability.WITS, Ability.POISE);

    /** Sections an exam covers; target is the grade aimed at (judgment: GOLD, promotion: current.higher(), renewal: current). */
    public static List<Ability> sections(int type, BadgeGrade target) {
        return switch (type) {
            case 4 -> ALL;
            case 5 -> target == BadgeGrade.GOLD ? ALL : ALL.subList(0, 3);
            case 6 -> ALL.subList(0, 1);
            default -> List.of();
        };
    }
    /** The questions in order, skipping sections the hall cannot stage. */
    public static List<ExamItem> items(int type, BadgeGrade target, ExamHall hall) {
        var out = new ArrayList<ExamItem>();
        if (type == 3) { out.add(new SimpleCheckItem()); return out; }
        if (hall == null) return out;
        if (type == 6) { if (hall.supports(Ability.MANNERS)) { out.add(new SocialFeedItem()); out.add(new SocialToiletItem()); } return out; }
        for (var a : sections(type, target)) {
            if (!hall.supports(a)) continue;
            switch (a) {
                case MANNERS -> { out.add(new SocialFeedItem()); out.add(new SocialToiletItem()); out.add(new SocialSleepItem()); out.add(new SocialFightItem()); }
                case ACADEMICS -> { out.add(new OralSumItem()); out.add(new OralReadItem()); }
                case WITS -> { out.add(new WitsHideItem()); out.add(new WitsPromiseItem()); if (target == BadgeGrade.GOLD) out.add(new WitsInterviewItem()); }
                case POISE -> { if (target == BadgeGrade.GOLD) { out.add(new PoiseWildItem()); out.add(new PoiseTroubleItem()); } }
            }
        }
        return out;
    }
    /** Pass marks of a grade (docs/PET_SHOP.md section 3); -1 (untested) never passes. */
    public static boolean meets(BadgeGrade g, int[] s) {
        return switch (g) {
            case NONE -> true;
            case BRONZE -> s[0] >= 30;
            case SILVER -> s[0] >= 60 && s[1] >= 50 && s[2] >= 50;
            case GOLD -> s[0] >= 75 && s[1] >= 70 && s[2] >= 70 && s[3] >= 70;
        };
    }
}
