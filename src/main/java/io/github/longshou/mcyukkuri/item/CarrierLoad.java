package io.github.longshou.mcyukkuri.item;

import java.util.Map;

/**
 * Basket sizes, what each growth stage takes, and whether a body is weak enough to be taken at all.
 * Pure arithmetic only, so the unit test runs without Minecraft and the item and the capture refusal read the same numbers.
 */
public final class CarrierLoad {
    /** Plain carries one adult, fine two, large three. */
    public enum Grade {
        PLAIN(4), FINE(8), LARGE(12);
        private final int capacity;
        Grade(int capacity) { this.capacity = capacity; }
        public int capacity() { return capacity; }
    }

    private static final Map<String, Integer> STAGE_LOAD = Map.of("adult", 4, "subadult", 2, "juvenile", 2, "baby", 1);

    private CarrierLoad() {}

    public static int stageLoad(String stage) { return STAGE_LOAD.get(stage); }

    public static boolean fits(Grade grade, int load, int added) { return load + added <= grade.capacity; }

    /** A timid body yields to moderate weakness. Defiance and esteem can shift that threshold up to .65. */
    public static double resistance(double insistence, double esteem, double fear) {
        return .35 + .15 * insistence + .10 * esteem / 100 + .05 * (1 - fear / 100.0);
    }

    /** A body goes in only while its weakness exceeds its resistance. */
    public static boolean overpowered(float weakness, double resistance) { return weakness > resistance; }
}
