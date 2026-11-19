package io.github.longshou.mcyukkuri.badge;

import java.util.Locale;
import java.util.function.Predicate;

public final class BadgeRequirements {
    public static final int TALENT = 85;

    public record Candidate(int waiting, int toilet, int foodGuard, int manners, int greeting, int cap,
                            boolean helpful, boolean considerate, int talent) {}

    public enum Need {
        WAIT_2(2, c -> c.waiting() >= 2),
        TOILET_2(2, c -> c.toilet() >= 2),
        GREETING_1(2, c -> c.greeting() >= 1),
        COUNT_3(2, c -> c.cap() >= 3),
        FOOD_GUARD_2(3, c -> c.foodGuard() >= 2),
        MANNERS_2(3, c -> c.manners() >= 2),
        GREETING_2(3, c -> c.greeting() >= 2),
        COUNT_5(3, c -> c.cap() >= 5),
        HELPFUL(3, Candidate::helpful),
        WAIT_3(4, c -> c.waiting() >= 3),
        TOILET_3(4, c -> c.toilet() >= 3),
        FOOD_GUARD_3(4, c -> c.foodGuard() >= 3),
        MANNERS_3(4, c -> c.manners() >= 3),
        GREETING_3(4, c -> c.greeting() >= 3),
        COUNT_8(4, c -> c.cap() >= 8),
        CONSIDERATE(4, Candidate::considerate),
        TALENT_85(4, c -> c.talent() >= TALENT);

        private final int grade;
        private final Predicate<Candidate> met;

        Need(int grade, Predicate<Candidate> met) {
            this.grade = grade;
            this.met = met;
        }

        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    private static final Need[] NEEDS = Need.values();

    private BadgeRequirements() {}

    /** Returns the first missing requirement in grade order, or null if all requirements are met. */
    public static Need firstMissing(int targetGrade, Candidate candidate) {
        for (Need need : NEEDS) {
            if (need.grade > targetGrade) break;
            if (!need.met.test(candidate)) return need;
        }
        return null;
    }

    public static int talent(int intelligence, int selfControl, int friendliness, int attachment) {
        return Math.round((intelligence + selfControl + friendliness + attachment) / 4f);
    }
}
