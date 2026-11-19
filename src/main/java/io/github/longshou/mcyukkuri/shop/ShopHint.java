package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.social.CharacterWords;
import java.util.Locale;

/** Append new hints to preserve the wire IDs. */
public enum ShopHint {
    TIMID, BOLD, SLOW_TO_WARM, FRIENDLY, INDEPENDENT, ATTACHED, IMPATIENT, PATIENT, SLOW_LEARNER, QUICK_LEARNER, EVEN_TEMPERED,
    HEALTHY, IN_CARE, RECOVERING, TENSE, NEW_TO_TRAINING, SOME_TRAINING, WELL_TRAINED, STANDOUT,
    SUBMISSIVE, DEFIANT, PANICKY, ENDURING, SLOW_TO_REACT, QUIET, STEADY,
    FRAGILE_BABY, HURT, TOUGH, HUNGRY, DIRTY, HAIRY, CONGENITAL, PREVENTION, SETTLED,
    CARING, SELFISH, COCKY, CURIOUS;

    public static ShopHint character(CharacterWords word) {
        return switch (word) {
            case TIMID -> TIMID;
            case BOLD -> BOLD;
            case PATIENT -> PATIENT;
            case RESTLESS -> IMPATIENT;
            case CARING -> CARING;
            case SELFISH -> SELFISH;
            case COCKY -> COCKY;
            case CURIOUS -> CURIOUS;
            case EVEN_TEMPERED -> EVEN_TEMPERED;
        };
    }

    public String translationKey(boolean oni) {
        return "shop.mcyukkuri.info." + (oni ? "oni." : "pet.") + name().toLowerCase(Locale.ROOT);
    }
}
