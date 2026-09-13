package io.github.longshou.mcyukkuri.family;

/** Loaded simulation time. Childhood is independent of the adult lifespan. */
public final class GrowthTiming {
    public static final long DEFAULT_MATURITY = 48000;
    public static final long DEFAULT_ADULT_LIFESPAN = 2160000;
    private GrowthTiming() {}

    public static long legacyMaturity(long lifespan) { return Math.max(1, lifespan / 3); }
    public static long migratedMaturity(long oldLifespan, long configuredMaturity) {
        return Math.max(200, Math.round(Math.max(600, oldLifespan) / 72000.0 * configuredMaturity));
    }
    public static long migratedLifespan(long oldLifespan, long configuredMaturity, long configuredAdultLifespan) {
        return migratedMaturity(oldLifespan, configuredMaturity)
                + Math.max(400, Math.round(Math.max(600, oldLifespan) / 72000.0 * configuredAdultLifespan));
    }
    /** Preserve childhood progress and the proportion of adult life already used. */
    public static long migrateAge(long age, long oldLifespan, long newMaturity, long newLifespan) {
        long oldMaturity = legacyMaturity(oldLifespan);
        if (age <= oldMaturity) return Math.round(Math.max(0, age) * (double)newMaturity / oldMaturity);
        return newMaturity + Math.round((age - oldMaturity) * (double)(newLifespan - newMaturity)
                / Math.max(1, oldLifespan - oldMaturity));
    }
    public static long migrateNourishment(long nourishment, long oldLifespan, long newMaturity) {
        return Math.round(Math.max(0, nourishment) * (double)newMaturity / legacyMaturity(oldLifespan));
    }
}
