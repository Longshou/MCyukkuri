package io.github.longshou.mcyukkuri.family;

/** Fixed loaded simulation time. Childhood is independent of nourishment and adult lifespan. */
public final class GrowthTiming {
    public static final long BABY_END = 72_000;
    public static final long SUBADULT_START = 216_000;
    public static final long ADULT_START = 336_000;
    public static final long ELDER_START = 948_000;
    public static final long LIFESPAN = 1_056_000;
    /** Compatibility name for callers which need the sole reproductive-age boundary. */
    public static final long DEFAULT_MATURITY = ADULT_START;
    /** The adult segment of the fixed lifespan. */
    public static final long DEFAULT_ADULT_LIFESPAN = LIFESPAN - ADULT_START;
    private GrowthTiming() {}
}
