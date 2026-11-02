package io.github.longshou.mcyukkuri.aquatic;

/** First-voyage desire from body/seat size ratio, with boldness moving the readiness band. */
final class VoyageReadiness {
    private VoyageReadiness() {}

    static double desire(double ratio, double boldness, double from, double to, double boldShift, double strong) {
        double shifted = ratio + (boldness - 50) / 50 * boldShift;
        if (shifted <= from) return 0;
        if (shifted >= to) return strong;
        return strong * (shifted - from) / (to - from);
    }
}
