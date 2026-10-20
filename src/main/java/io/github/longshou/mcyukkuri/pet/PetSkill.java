package io.github.longshou.mcyukkuri.pet;

import java.util.Locale;

/** Concrete things a yukkuri has actually learned to do. These are not training scores. */
public enum PetSkill {
    WAIT,
    FOOD_BOWL,
    TOILET,
    BEDTIME,
    READING,
    ARITHMETIC;

    public String key() { return name().toLowerCase(Locale.ROOT); }
}
