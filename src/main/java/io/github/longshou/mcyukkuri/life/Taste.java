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

    public enum Verdict { SAVOUR, PLAIN, GRUMBLE, REFUSE }

    // Project-design numbers, all justified in docs/FEATURES.md and in the report:
    static final int EASY = 30;                 // 舌肥 below this enjoys anything, even coarse food
    static final int COARSE_CEILING = 30, ORDINARY_CEILING = 70, FINE_CEILING = 140, TREAT_CEILING = 140;
    static final int HUNGER_RELIEF = 40;        // full nutrition -> no relief; starving -> +40 to the ceiling
    static final float RELIEF_FROM = 55f, RELIEF_TO = 25f;   // relief 0 at nutrition>=55, all of it at <=25
    static final int GRUMBLE_BAND = 10;         // inside this margin the serving is eaten with a complaint

    /** Innate spread, seeded by the individual's own UUID like YukkuriPersonality: 30-70. */
    public static int innate(UUID id) {
        return new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits()).nextInt(41) + 30;
    }

    /**
     * How choosey this body is right now: 舌肥 shifted by the values that really change the answer.
     * A proud body is harder to please (+0.4 per point of self-esteem above 50), a familiar feeder softens it
     * (-8 at closeness>=10) and a disliked one hardens it (+6 below 0). Slaves and predators never refuse at all;
     * that gate stays in LifeMeal.verdict, so this read adds no slave or dirt term of its own.
     */
    public static int effective(int pickiness, double esteem, int closeness) {
        double value = pickiness + (esteem - 50) * .4;
        if (closeness >= 10) value -= 8;
        else if (closeness < 0) value += 6;
        return clamp((int) Math.round(value), 0, 100);
    }

    /** The verdict for one serving. margin = ceiling(grade) + hunger relief - taste. */
    public static Verdict judge(int taste, Grade grade, float nutrition) {
        if (taste < EASY) return Verdict.SAVOUR;
        int ceiling = switch (grade) {
            case COARSE -> COARSE_CEILING;
            case ORDINARY -> ORDINARY_CEILING;
            case FINE -> FINE_CEILING;
            case TREAT -> TREAT_CEILING;
        };
        int margin = ceiling + (int) Math.round(HUNGER_RELIEF * rampDown(nutrition, RELIEF_FROM, RELIEF_TO)) - taste;
        if (margin <= 0 && nutrition > RELIEF_TO) return Verdict.REFUSE;
        if (margin < GRUMBLE_BAND) return Verdict.GRUMBLE;
        return Verdict.PLAIN;
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

    /** 0 at/above from, 1 at/below to, linear between. */
    private static double rampDown(float value, float from, float to) {
        return clamp((from - value) / (from - to), 0, 1);
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }

    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }

    private Taste() {}
}
