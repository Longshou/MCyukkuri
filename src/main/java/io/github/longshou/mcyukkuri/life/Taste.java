package io.github.longshou.mcyukkuri.life;

import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/** 舌肥 (FG-03, picky-eating degree): the one reading of how choosey a body is about one serving. */
public final class Taste {
    public enum Grade {
        COARSE, ORDINARY, FINE, TREAT;

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Verdict { SAVOUR, PLAIN, GRUMBLE, RELUCTANT, REFUSE }

    /** Innate spread, seeded by the individual's own UUID like YukkuriPersonality: 30-70. */
    public static int innate(UUID id) {
        return new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits()).nextInt(41) + 30;
    }

    /**
     * How choosey this body is right now: 舌肥 shifted by the values that really change the answer.
     * A proud body is harder to please (+0.4 per point of self-esteem above 50), a familiar feeder softens it
     * (up to -12 at closeness 25) and a disliked one hardens it (up to +10 at -20). Slaves and predators never refuse at all;
     * that gate stays in LifeMeal.verdict, so this read adds no slave or dirt term of its own.
     */
    public static int effective(int pickiness, double esteem, int closeness) {
        double value = pickiness + (esteem - 50) * .4 - 12 * ramp(closeness, 0, 25) + 10 * ramp(-closeness, 0, 20);
        return clamp((int) Math.round(value), 0, 100);
    }

    /** Grade sets the unadjusted gap. Hunger can make a disliked serving edible, and starvation can override refusal. */
    public static Verdict judge(int taste, Grade grade, float nutrition) {
        int ceiling = switch (grade) {
            case COARSE -> 30;
            case ORDINARY -> 70;
            case FINE -> 110;
            case TREAT -> 140;
        };
        int rawGap = ceiling - taste;
        int margin = rawGap + (int) Math.round(40 * (1 - ramp(nutrition, 25, 55)));
        if (taste < 25 || (rawGap >= 0 && margin >= 45)) return Verdict.SAVOUR;
        if (margin <= 0 && nutrition > 25) return Verdict.REFUSE;
        if (nutrition <= 30 && rawGap <= -15) return Verdict.RELUCTANT;
        if (margin < 12 || rawGap < 0) return Verdict.GRUMBLE;
        return Verdict.PLAIN;
    }

    /** Composure and familiarity temper the way a body rejects a hand-fed serving. */
    public static boolean spitsBack(int selfControl, double esteem, int closeness) {
        double rudeness = .55 * (1 - ramp(selfControl, 20, 75)) + .30 * ramp(esteem, 40, 75)
                - .35 * ramp(closeness, 0, 20) + .20 * ramp(-closeness, 0, 20);
        return rudeness >= .45;
    }

    /** How one swallowed serving moves 舌肥: a treat +2, fine food +1, ordinary 0, coarse -2, and one more point down for any meal taken while very hungry (nutritionBefore < 30). */
    public static int afterMeal(Grade grade, float nutritionBefore) {
        int delta = switch (grade) {
            case COARSE -> -2;
            case ORDINARY -> 0;
            case FINE -> 1;
            case TREAT -> 2;
        };
        return nutritionBefore < 30 ? delta - 1 : delta;
    }

    private static double ramp(double value, double from, double to) {
        return clamp((value - from) / (to - from), 0, 1);
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }

    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }

    private Taste() {}
}
