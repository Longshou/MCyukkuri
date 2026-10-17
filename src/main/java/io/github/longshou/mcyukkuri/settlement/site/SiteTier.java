package io.github.longshou.mcyukkuri.settlement.site;

/**
 * Size classes of a settlement site (project-original numbers). A band is one or two households, a village three to
 * five with a common longhouse, and DEMO is the fixed 15 x 15 exhibit that never generates on its own.
 */
public enum SiteTier {
    /** Footprint half-extents: {@code core} is the hedge radius, {@code bound} also covers the meadow ring. */
    BAND(13.5, 19, 3, 1, 2, 4, 8, false),
    VILLAGE(22, 29, 6, 3, 5, 10, 20, true),
    DEMO(0, 11, 2, 1, 2, 6, 6, true);
    public final double hedge;
    public final int bound, homes, minHouseholds, maxHouseholds, minPopulation, maxPopulation;
    /** True when the site has a common longhouse (the leader's seat, or a shared winter nest without a leader). */
    public final boolean hall;
    SiteTier(double hedge, int bound, int homes, int minHouseholds, int maxHouseholds, int minPopulation, int maxPopulation, boolean hall) {
        this.hedge = hedge; this.bound = bound; this.homes = homes; this.minHouseholds = minHouseholds; this.maxHouseholds = maxHouseholds;
        this.minPopulation = minPopulation; this.maxPopulation = maxPopulation; this.hall = hall;
    }
    public boolean generates() { return this != DEMO; }
    public static SiteTier byName(String name) {
        for (var tier : values()) if (tier.name().equalsIgnoreCase(name)) return tier;
        return null;
    }
}
