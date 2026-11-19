package io.github.longshou.mcyukkuri.life;

final class WaterPortions {
    static final int CAPACITY = 8;

    static int refill(int water, boolean bucket) {
        return bucket ? CAPACITY : Math.min(CAPACITY, water + 3);
    }

    static int spend(int water) { return Math.max(0, water - 1); }

    private WaterPortions() {}
}
