package io.github.longshou.mcyukkuri.cognition;

public final class SkillMath {
    private static final int[] THRESHOLDS = {0, 2, 5, 9};
    private static final int BINARY = 2;
    private static final double[] CAN = {.15, .55, .80, .95};

    private SkillMath() {}

    public enum Attempt { DONE, CLUMSY, DEFIED }

    public static int grade(int points, boolean graded) {
        if (!graded) return points >= BINARY ? 1 : 0;
        for (int grade = THRESHOLDS.length - 1; grade > 0; grade--) {
            if (points >= THRESHOLDS[grade]) return grade;
        }
        return 0;
    }

    public static int pointsForGrade(int grade, boolean graded) {
        return graded ? THRESHOLDS[grade] : grade * BINARY;
    }

    public static double can(int grade) {
        return CAN[grade];
    }

    public static double will(double patient, int stress, int neglect, double obedience) {
        return Math.clamp(.30 + .60 * patient + .20 * (obedience - .5) - .004 * stress - .003 * neglect, .10, 1);
    }

    public static Attempt attempt(int grade, double will, double canRoll, double willRoll) {
        if (canRoll >= can(grade)) return Attempt.CLUMSY;
        return willRoll >= will ? Attempt.DEFIED : Attempt.DONE;
    }

    public static int capBase(int intelligence) {
        return 1 + (intelligence >= 35 ? 1 : 0) + (intelligence >= 65 ? 1 : 0);
    }

    public static int capCeiling(int intelligence) {
        return 3 + intelligence / 15;
    }
}
