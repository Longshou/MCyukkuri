package io.github.longshou.mcyukkuri.family;

public final class PentUpMath {
    private PentUpMath() {}

    public enum Episode { NONE, HEAT, HUNT }

    // A healthy adult on its own reaches the middle band in about 1.4 loaded days.
    private static final float DAY_GAIN = 40;
    public static final float ALICE_HUNT = 90;

    static boolean eligible(boolean adult, boolean sterile, boolean elder, boolean canConceive, boolean predator) {
        return adult && !sterile && !elder && canConceive && !predator;
    }

    static float rate(boolean fed, boolean healthy, boolean stressed, boolean hungry, boolean ill,
                      boolean partnerInSight, boolean alice) {
        if (hungry || ill) return 0;
        return DAY_GAIN / 24_000 * (fed ? 1 : .5f) * (healthy ? 1 : .5f) * (stressed ? .5f : 1)
                * (partnerInSight ? 1.5f : 1) * (alice ? 2 : 1);
    }

    public static float band(double patient) { return (float) (45 + 25 * patient); }

    public static Episode episode(float pressure, double patient, boolean impulsiveAlice, boolean mayPair,
                                  int cooldown, int recovery) {
        if (cooldown > 0 || recovery > 0) return Episode.NONE;
        if (impulsiveAlice && pressure >= ALICE_HUNT) return Episode.HUNT;
        return mayPair && pressure >= band(patient) ? Episode.HEAT : Episode.NONE;
    }
}
