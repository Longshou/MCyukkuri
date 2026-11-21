package io.github.longshou.mcyukkuri.cognition;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/**
 * Read-only cognition rules.
 *
 * <p>Intelligence is one of personality's five authoritative axes. This class owns no parallel
 * saved score, inheritance snapshot, or teaching counter.</p>
 */
public final class Cognition {
    private Cognition() {}

    public static int intelligence(YukkuriEntity entity) {
        return entity.life().personality().intelligence();
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
}
