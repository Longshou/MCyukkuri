package io.github.longshou.mcyukkuri.family;

final class CanopyBirthGeometry {
    /** Centre the ripe row over the pad while the mother's box clears its raised lip by the arrival tolerance. */
    static double depth(double first, double last, double motherWidth) {
        return Math.max((first + last) / 2, .5 + motherWidth / 2 + .02);
    }
    private CanopyBirthGeometry() {}
}
