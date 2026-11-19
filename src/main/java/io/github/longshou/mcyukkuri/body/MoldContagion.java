package io.github.longshou.mcyukkuri.body;

/** A five-second contact sample. Dry, clean skin needs six minutes against early patches. */
public final class MoldContagion {
    public static final int SAMPLE = 100, THRESHOLD = 72;
    public static final double TOUCH = .25;
    private static final int[] SHEDDING = {0, 1, 2, 3, 3};

    private MoldContagion() {}

    public static int gain(int sourceTier, int hygiene) {
        return SHEDDING[sourceTier] * (hygiene < 40 ? 3 : hygiene < 70 ? 2 : 1);
    }

    public static int next(int exposure, int gain) {
        return gain > 0 ? exposure + gain : Math.max(0, exposure - 1);
    }
}
