package io.github.longshou.mcyukkuri.settlement;

/** Village beds measure the amount of inhabited street. The target limits arrivals only. */
final class StrayDensity {
    static final int RADIUS = 64;

    static boolean inFootprint(int dx, int dz) {
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    static int target(int beds, boolean night, boolean rain) {
        if (beds == 0) return 0;
        int fairDay = Math.min(6, 2 + beds / 4);
        return Math.max(1, fairDay - (night ? 1 : 0) - (rain ? 1 : 0));
    }

    static boolean admits(int vacancies, int nearby, int known, int localCap, int familyCap, boolean existing) {
        int added = existing ? 0 : 1;
        return vacancies > 0 && nearby + added <= localCap && known + added <= Math.min(localCap, familyCap);
    }

    private StrayDensity() {}
}
