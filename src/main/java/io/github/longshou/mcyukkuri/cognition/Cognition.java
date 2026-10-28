package io.github.longshou.mcyukkuri.cognition;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData;
import io.github.longshou.mcyukkuri.event.WildEvents;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * Read-only cognition rules.
 *
 * <p>Intelligence is one of personality's five authoritative axes. This class owns no parallel
 * saved score, inheritance snapshot, or teaching counter.</p>
 */
public final class Cognition {
    public enum TeachingResult { APPLIED, INELIGIBLE }

    private Cognition() {}

    public static int intelligence(YukkuriEntity entity) {
        return entity.life().personality().intelligence();
    }

    public static boolean canTeach(YukkuriEntity teacher, YukkuriEntity student) {
        return teacher != student && teacher.level() instanceof ServerLevel && teacher.level() == student.level()
                && teacher.childhood().canLearn() && student.childhood().canLearn()
                && WildEvents.safe(teacher) && WildEvents.safe(student)
                && !teacher.life().hasUrgentNeed() && !student.life().hasUrgentNeed()
                && teacher.life().mealTicks() == 0 && student.life().mealTicks() == 0
                && teacher.distanceToSqr(student) <= 16 && teacher.hasLineOfSight(student) && student.hasLineOfSight(teacher)
                && WildEvents.friendly(teacher, student) && intelligence(teacher) > intelligence(student);
    }


    /** One valid lesson has one probability draw, supplied by the event's real completion point. */
    public static boolean learningSucceeds(YukkuriEntity student, double roll) {
        return Double.isFinite(roll) && roll >= 0 && roll < 1
                && roll < student.life().personality().learningProbability(student.mind().stressView());
    }

    /** A search attempt only chooses whether this actual target was identified; it never creates food. */
    public static boolean forestFindSucceeds(YukkuriEntity entity, double roll) {
        return Double.isFinite(roll) && roll >= 0 && roll < 1
                && roll < Math.clamp(.35 + .006 * intelligence(entity), .35, .95);
    }

    /**
     * Confirms a real teacher/student demonstration. It grants no generic cognition points: this
     * card has no named transferable skill. Named lessons complete their PetSkill at their own
     * actual finish point.
     */
    public static TeachingResult commitTeaching(YukkuriEntity teacher, YukkuriEntity student, UUID classroom) {
        if (!(student.level() instanceof ServerLevel level) || classroom == null) return TeachingResult.INELIGIBLE;
        var scene = WildEventData.get(level).scene(classroom);
        return scene != null && scene.active() && scene.kind == WildEventData.Kind.CARD
                && scene.card.equals("teach") && scene.members.equals(List.of(teacher.getUUID(), student.getUUID()))
                && scene.sourceCount == 1 && scene.responses == 3 ? TeachingResult.APPLIED : TeachingResult.INELIGIBLE;
    }
}
