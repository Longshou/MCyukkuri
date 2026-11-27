package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.perception.Sight;

/**
 * Pure boldness-scaled intervals and presentation choices for thunder fright after a perceived lightning clap.
 * A body that never heard a clap (or just loaded from disk) is not frightened; once it hears one, fright holds for a
 * boldness-scaled minimum after the clap and while the thunderstorm persists overhead, capped so an endless silent storm
 * still closes cleanly.
 */
public final class ThunderFear {
    private static final int CLAP_BOLD = 80, CLAP_SPAN = 120;
    private static final int STORM_BOLD = 200, STORM_SPAN = 400;
    private static final double ASHORE_BOLD_DROP = .65;
    private static final int TIMID_MAX = 35, BOLD_MIN = 70;

    private ThunderFear() {}

    /** Minimum ticks a heard thunder clap frightens this body even if the sky clears right after (80 bold .. 200 timid). */
    public static int clapHold(int boldness) {
        return (int) Math.round(CLAP_BOLD + CLAP_SPAN * (1 - Sight.ramp(boldness, 20, 80)));
    }

    /** Longest ticks one clap holds fright while the storm keeps thundering overhead (200 bold .. 600 timid). */
    public static int stormCap(int boldness) {
        return (int) Math.round(STORM_BOLD + STORM_SPAN * (1 - Sight.ramp(boldness, 20, 80)));
    }

    /** Whether thunder fright is active at {@code now} given the tick of the last perceived clap. */
    public static boolean active(long now, long heardAt, boolean thundering, int boldness) {
        if (heardAt <= Long.MIN_VALUE / 4 || now < heardAt) return false;
        long elapsed = now - heardAt;
        return elapsed < clapHold(boldness) || thundering && elapsed < stormCap(boldness);
    }

    /** How strongly heard thunder pulls an adult ashore: full base desire for timid (<=20), down to 35% for bold (>=80). */
    public static double ashoreNeed(double base, int boldness) {
        return base * (1 - ASHORE_BOLD_DROP * Sight.ramp(boldness, 20, 80));
    }

    /** Scared face shown while hiding from heard thunder, scaled by boldness. */
    public static String hideFace(int boldness) {
        return boldness <= TIMID_MAX ? "horrified" : boldness >= BOLD_MIN ? "surprise" : "teary";
    }

    /** Dialogue event spoken when hiding from heard thunder, scaled by boldness. */
    public static String hideLine(int boldness) {
        return boldness <= TIMID_MAX ? "afraid" : "thunder";
    }

    /** Relieved face shown once the thunder fright ends cleanly. */
    public static String calmFace(int boldness) {
        return boldness >= BOLD_MIN ? "happy" : "closed_smile";
    }
}
