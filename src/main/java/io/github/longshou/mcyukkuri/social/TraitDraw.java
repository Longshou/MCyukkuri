package io.github.longshou.mcyukkuri.social;

import java.util.Random;

/** Deterministic draws for new innate traits. Each axis and draw uses its own salt. */
public final class TraitDraw {
    static final int WILD_CENTRE = 50;
    private static final int WILD_SD = 15;
    private static final int WILD_MIN = 10;
    private static final int WILD_MAX = 90;
    private static final int INHERIT_NOISE = 15;
    private static final long AXIS_SALT = 0x9E3779B97F4A7C15L;
    private static final long WILD_SALT = 0xD1B54A32D192ED03L;
    private static final long INHERIT_SALT = 0x94D049BB133111EBL;
    private static final long UNKNOWN_SALT = 0xBF58476D1CE4E5B9L;

    private TraitDraw() {}

    public static int wild(long seed, int axis, int centre) {
        var random = new Random(seed ^ (AXIS_SALT * (axis + 1L)) ^ WILD_SALT);
        return Math.clamp((int) Math.round(centre + WILD_SD * random.nextGaussian()), WILD_MIN, WILD_MAX);
    }

    static double unknownParent(long seed, int axis) {
        return wild(seed ^ UNKNOWN_SALT, axis, WILD_CENTRE) / 100.0;
    }

    public static int inherit(long seed, int axis, double first, double second, int min, int max) {
        var random = new Random(seed ^ (AXIS_SALT * (axis + 1L)) ^ INHERIT_SALT);
        double noise = (random.nextInt(2 * INHERIT_NOISE + 1) - INHERIT_NOISE) / 100.0;
        double normalized = Math.clamp((first + second) / 2 + noise, 0, 1);
        return Math.clamp((int) Math.round(min + normalized * (max - min)), min, max);
    }
}
