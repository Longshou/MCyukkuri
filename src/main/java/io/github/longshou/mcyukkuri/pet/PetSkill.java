package io.github.longshou.mcyukkuri.pet;

import java.util.Locale;

/** Concrete skills. The original six ordinals remain the exam and legacy save bit positions. */
public enum PetSkill {
    WAIT,
    FOOD_BOWL,
    TOILET,
    BEDTIME,
    READING,
    ARITHMETIC,
    GREETING,
    HARNESS;

    private static final int[] LEVEL_XP = {0, 4, 16, 40, 80};
    static int xpForLevel(int tier) { return LEVEL_XP[tier]; }
    static int maximumXp() { return LEVEL_XP[LEVEL_XP.length - 1]; }
    public static int levelAt(int experience) {
        for (int tier = LEVEL_XP.length - 1; tier > 0; tier--) if (experience >= LEVEL_XP[tier]) return tier;
        return 0;
    }

    public String key() { return name().toLowerCase(Locale.ROOT); }
}
