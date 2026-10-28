package io.github.longshou.mcyukkuri.house;

/** Shared server/client clock; matching model metadata is checked by the asset validator. */
public final class HouseCrawlMotion {
    public static final int PERIOD_TICKS = 12;
    public static final int GATHER_TICK = 5;
    public static final int SETTLE_TICK = 10;
    public static final double TRAVEL_UNITS = 1.92;
    public static final double DRAG_SHARE = .60;

    private HouseCrawlMotion() {}

    private static double smooth(double value) {
        double q = Math.clamp(value, 0, 1);
        return q*q*(3-2*q);
    }

    public static double gathered(double ticks) {
        return smooth((ticks-GATHER_TICK)/(SETTLE_TICK-GATHER_TICK));
    }

    /** Continuous dragging with extra advance as the abdomen gathers. */
    public static double advance(long elapsed) {
        int phase = (int)Math.floorMod(elapsed, PERIOD_TICKS);
        double gather = Math.max(0, gathered(phase)-gathered(phase-1));
        return TRAVEL_UNITS/16 * (DRAG_SHARE/PERIOD_TICKS + (1-DRAG_SHARE)*gather);
    }
}
