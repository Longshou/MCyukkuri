package io.github.longshou.mcyukkuri.family;

/** Wing practice earned at contact and the share of a fall left to the body. */
final class FledgeMath {
    static double threshold(int boldness) { return .75 - .30 * boldness / 100; }
    static double practise(double progress, boolean landedHop) {
        return Math.min(1, progress + (landedHop ? .08 : .04));
    }
    static float effectiveFall(float height, double progress) {
        return (float)(height * (1 - .8 * progress));
    }
    private FledgeMath() {}
}
