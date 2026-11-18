package io.github.longshou.mcyukkuri.motor.geom;

/** A passive spring loses energy on every contact. Only a deliberate jump can put energy back in. */
public final class TrampolineSpring {
    public static final double RESTITUTION = .85;
    // The volume of a standing adult Reimu or Marisa, in cubic blocks.
    private static final double ADULT_VOLUME = .75 * .75 * .53;

    private TrampolineSpring() {}

    public static double restitution(double weight, double fitness, boolean young) {
        return (.65 + .30 * fitness) / (1 + .18 * weight / ADULT_VOLUME) * (young ? .70 : 1);
    }

    public static double rebound(double impact, double restitution, double gravity) {
        double speed = impact * restitution;
        // An impulse which cannot overcome the next gravity step is standing contact.
        return speed > gravity ? speed : 0;
    }

    public static double jump(double jumpSpeed, double rebound) {
        return jumpSpeed + Math.max(0, rebound);
    }

    public static double livingHeight(double rebound) {
        return Ballistic.peak((rebound - Ballistic.GRAVITY) * Ballistic.VERTICAL_DRAG);
    }
}
