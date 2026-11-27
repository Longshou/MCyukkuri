package io.github.longshou.mcyukkuri.life;

/**
 * The pure rules of cold stiffening (docs/features/wild-ecology.md, 体感温度): how long a body that has taken in all the cold it can
 * keeps its window, how that window drains in the cold and refills in the warm, and where the thaw begins. The window is {@code stiffLeft},
 * in ticks; every number is a project design and an interval, shifted by the individual. No entity type, so it is unit-testable.
 */
final class ColdStiffness {
    /** The exposure accumulates and the window is read on the same 20-tick sample. */
    static final int SAMPLE = 20;
    /** The window is {@code windowShare} of the exposure it took to stiffen, never under 0.4 or over 1.4 of it (a fat adult lasts, a hungry baby does not). */
    static final double MIN_SHARE = .4, MAX_SHARE = 1.4;
    /** Largest window in ticks: a stiff body is never a whole-winter sleeper. */
    static final int MAX_LEFT = 6000;
    /** The felt-temperature deficit counted at least this much, so a body only just under the line still has a window of ticks, not hours. */
    static final double MIN_DEFICIT = .3;
    /** Between the freezing and the cold line the window still drains, a quarter as fast: it reads as not warm enough, and never waits for ever. */
    static final double SLOW_DRAIN = .25;
    /** Thawing begins once the window is back to this share of the one it entered with and the place is warm (the hysteresis). */
    static final double THAW_LINE = .9;
    /** A thawed body is chilled to this share of the exposure that stiffens it, and falls from there by the ordinary rule. */
    static final double THAWED_EXPOSURE = .9;
    /** After a thaw (project design): the body has spent itself. */
    static final float THAW_NUTRITION = 8, THAW_ENERGY = 15;

    private ColdStiffness() {}

    /** Nourished bodies hold out longer: 0.6 when empty, 1.2 when full. */
    static double nutritionFactor(double nutrition) { return .6 + .6 * Math.clamp(nutrition, 0, 100) / 100; }

    /** Bulk keeps heat: the square root of the size against the standard adult, between 0.75 and 1.25. */
    static double sizeFactor(double sizeRatio) { return Math.clamp(Math.sqrt(Math.max(0, sizeRatio)), .75, 1.25); }

    /** A hurt body has less to burn: 0.5 at no health, 1 at full. */
    static double healthFactor(double healthRatio) { return .5 + .5 * Math.clamp(healthRatio, 0, 1); }

    /** The window as a share of the exposure that stiffened it; {@code base} is the configured typical share (0.75). */
    static double windowShare(double base, double nutrition, double sizeRatio, double healthRatio) {
        return Math.clamp(base * nutritionFactor(nutrition) * sizeFactor(sizeRatio) * healthFactor(healthRatio), MIN_SHARE, MAX_SHARE);
    }

    /** The window in ticks: that share of the stiffening exposure, spent at the rate this age chills at this deficit. */
    static int windowTicks(double exposureToStiffen, double share, double ageRate, double deficit) {
        double ticks = exposureToStiffen * share / (ageRate * Math.max(deficit, MIN_DEFICIT));
        return (int) Math.min(MAX_LEFT, Math.round(ticks));
    }

    /**
     * What one sample does to the window: freezing drains it a tick per tick, the zone below the cold line drains it slowly, and a
     * place at or over the cold line refills it by {@code thawRate} times the rate the ordinary exposure falls there (a campfire about 1
     * a tick, a torch 0.4, a nest 0.5 at the default 0.5).
     */
    static double change(double felt, double coldLimit, double freezeLimit, double thawRate) {
        if (felt <= freezeLimit) return -SAMPLE;
        if (felt < coldLimit) return -SAMPLE * SLOW_DRAIN;
        return SAMPLE * thawRate * -ThermalComfort.exposureRate(felt, coldLimit, 1);
    }

    /** The window never refills past what the body entered with. */
    static double advance(double left, double max, double change) { return Math.min(max, left + change); }

    static boolean spent(double left) { return left <= 0; }

    /** Warm enough and back to the thaw line: the shell lets go. A cold sample never thaws, however full the window still is. */
    static boolean thawed(double left, double max, boolean warm) { return warm && left >= THAW_LINE * max; }

    /** The exposure at which a body that cannot stiffen yet (it is swimming, riding, in labour) freezes outright: the window past the stiffening one. */
    static double deathExposure(double exposureToStiffen, double share) { return exposureToStiffen * (1 + share); }
}
