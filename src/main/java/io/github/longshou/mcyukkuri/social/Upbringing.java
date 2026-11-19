package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.TraitBounds;

/** Earned points and the signed units towards the next point on one axis. */
record Upbringing(int points, int remainder) {
    static final int LIMIT = 25;
    private static final int UNITS = 4;

    Upbringing after(int units, boolean young, int innate, Trait trait, TraitBounds form) {
        int total = remainder + units * (young ? 2 : 1);
        return bounded(points + total / UNITS, total % UNITS, innate, trait, form);
    }

    static Upbringing bounded(int points, int remainder, int innate, Trait trait, TraitBounds form) {
        int lower = Math.max(-LIMIT, form.minimum(trait) - innate);
        int upper = Math.min(LIMIT, form.maximum(trait) - innate);
        int shift = Math.clamp(points, lower, upper);
        int progress = Math.clamp(remainder, -(UNITS - 1), UNITS - 1);
        if (shift == lower && progress < 0 || shift == upper && progress > 0) progress = 0;
        return new Upbringing(shift, progress);
    }
}
