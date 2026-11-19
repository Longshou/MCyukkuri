package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Random;

/** Deterministic draws for new innate traits. Each axis and draw uses its own salt. */
public final class TraitDraw {
    private static final int WILD_CENTRE = 50;
    // Seven points express a species tendency within the wider 15-point spread.
    private static final int SPECIES_SHIFT = 7;
    private static final int WILD_SD = 15;
    private static final int WILD_MIN = 10;
    private static final int WILD_MAX = 90;
    private static final int INHERIT_NOISE = 15;
    private static final long AXIS_SALT = 0x9E3779B97F4A7C15L;
    private static final long WILD_SALT = 0xD1B54A32D192ED03L;
    private static final long INHERIT_SALT = 0x94D049BB133111EBL;
    private static final long UNKNOWN_SALT = 0xBF58476D1CE4E5B9L;

    private TraitDraw() {}

    private static int centre(YukkuriVariant variant, Trait trait) {
        int shift = switch (variant) {
            case REIMU -> trait == Trait.ATTACHMENT ? 1 : 0;
            case MARISA -> switch (trait) {
                case BOLDNESS -> 1;
                case SELF_CONTROL -> -1;
                default -> 0;
            };
            case CHEN, HOUSE_REIMU, HOUSE_MARISA -> trait == Trait.BOLDNESS ? -1 : 0;
            case ALICE -> trait == Trait.SELF_CONTROL || trait == Trait.FRIENDLINESS ? 1 : 0;
            case PATCHOULI -> switch (trait) {
                case INTELLIGENCE -> 1;
                case BOLDNESS -> -1;
                default -> 0;
            };
            case MYON -> switch (trait) {
                case BOLDNESS -> 1;
                case INTELLIGENCE -> -1;
                default -> 0;
            };
            case REMILIA, FLANDRE -> switch (trait) {
                case BOLDNESS -> 1;
                case FRIENDLINESS -> -1;
                default -> 0;
            };
        };
        return WILD_CENTRE + SPECIES_SHIFT * shift;
    }

    static int wild(long seed, Trait trait, YukkuriVariant variant) {
        return wild(seed, trait.ordinal(), centre(variant, trait));
    }

    static int wild(long seed, int axis, int centre) {
        var random = new Random(seed ^ (AXIS_SALT * (axis + 1L)) ^ WILD_SALT);
        return Math.clamp((int) Math.round(centre + WILD_SD * random.nextGaussian()), WILD_MIN, WILD_MAX);
    }

    static int unknownParent(long seed, Trait trait, YukkuriVariant childVariant) {
        return wild(seed ^ UNKNOWN_SALT, trait, childVariant);
    }

    static int inForm(double percent, int min, int max) {
        return min + (int) Math.round(percent * (max - min) / 100);
    }

    public static int inherit(long seed, int axis, int firstPercent, int secondPercent, int min, int max) {
        var random = new Random(seed ^ (AXIS_SALT * (axis + 1L)) ^ INHERIT_SALT);
        int noise = random.nextInt(2 * INHERIT_NOISE + 1) - INHERIT_NOISE;
        return inForm(Math.clamp((firstPercent + secondPercent) / 2.0 + noise, 0, 100), min, max);
    }
}
