package io.github.longshou.mcyukkuri.predator;

/** Open ground sight lines and branch height contribute equally across their design ranges. */
final class PerchScore {
    static final int MIN_HEIGHT = 4, MAX_HEIGHT = 14;

    static double score(int height, int visible, boolean midday, boolean shaded) {
        return .5 * (height - MIN_HEIGHT) / (MAX_HEIGHT - MIN_HEIGHT) + .5 * visible / 8
                + (midday && shaded ? .4 : 0);
    }

    static boolean eligible(int height, int visible, long cell, Long last, Long previous) {
        return height >= MIN_HEIGHT && height <= MAX_HEIGHT && visible >= 4
                && (last == null || cell != last) && (previous == null || cell != previous);
    }

    static boolean midday(long dayTime, boolean clear) {
        long time = Math.floorMod(dayTime, 24000);
        return clear && time >= 3500 && time < 8500;
    }
}
