package io.github.longshou.mcyukkuri.cognition;

import java.util.Locale;

public enum Ability {
    WAIT(true, true), TOILET(true, true), FOOD_GUARD(true, true), MANNERS(true, true), GREETING(true, true),
    COUNTING(true, true), DEN_SEAL(false, true), SAILING(false, false), MOLD_LORE(false, false),
    PAPER_COVER(false, false), LEAF_SWADDLE(false, false);

    public static final int READOUT_PROGRESS = 8;
    public final boolean domestic;
    public final boolean graded;
    private final String key = name().toLowerCase(Locale.ROOT);

    Ability(boolean domestic, boolean graded) {
        this.domestic = domestic;
        this.graded = graded;
    }

    public String key() { return key; }
    public String translationKey() { return "pet.mcyukkuri.skill." + key; }
}
