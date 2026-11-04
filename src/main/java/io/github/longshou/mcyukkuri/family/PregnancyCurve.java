package io.github.longshou.mcyukkuri.family;

/** The live brood's visible growth, and its inverse for a resting body's shore deadline. */
public final class PregnancyCurve {
    public static final float SWELL = .26f, LOW_WIDE = .16f;
    private PregnancyCurve() {}

    public static float belly(float progress, int born, int count) {
        float q = (float) Math.sqrt(Math.clamp(progress, 0f, 1f));
        return q * q * (3 - 2 * q) * (1 - .78f * born / Math.max(1, count));
    }

    public static float widthScale(float belly) { return (1 + SWELL * belly) * (1 + LOW_WIDE * belly); }

    /** Invert the unborn belly curve; delivery shrinkage cannot move the shore point. */
    public static double shoreFraction(double restingWidth, double limit) {
        double a = SWELL, b = LOW_WIDE;
        double belly = Math.clamp((Math.sqrt((a - b) * (a - b) + 4 * a * b * limit / restingWidth) - a - b) / (2 * a * b), 0, 1);
        double root = .5 - Math.sin(Math.asin(1 - 2 * belly) / 3);
        return root * root;
    }
}
