package io.github.longshou.mcyukkuri.motor.geom;

/** A passive spring loses energy on every contact; a deliberate jump or active play push re-adds energy up to the body's hop ability. */
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

    /**
     * Active landing during a play session: the mat reflects the impact passively, and the body adds a deliberate push
     * up to its own {@code hopSpeed} toward {@code targetSpeed}. Passive physics still bound the push: a heavy, tired
     * or timid body aims lower and cannot exceed {@code passive + hopSpeed}.
     */
    public static double activeRebound(double impact, double restitution, double gravity, double hopSpeed, double targetSpeed) {
        double passive = rebound(impact, restitution, gravity);
        if (hopSpeed <= 0 || targetSpeed <= 0) return passive;
        double push = Math.max(0, Math.min(hopSpeed, targetSpeed - passive));
        double speed = passive + push;
        return speed > gravity ? speed : 0;
    }

    /**
     * Target bounce apex (blocks) for a playing body: scales its own {@code hopHeight} by mat {@code restitution}
     * (weight, fitness and youth), boldness (timid bodies hold back, bold bodies push higher) and a 3-bounce build-up.
     */
    public static double targetApex(double hopHeight, double restitution, double boldnessRatio, int bounceIndex) {
        if (hopHeight <= 0 || restitution <= 0) return 0;
        double ramp = Math.min(1, .70 + .15 * Math.max(0, bounceIndex));
        double drive = .65 + .65 * Math.clamp(boldnessRatio, 0, 1);
        return hopHeight * restitution * drive * ramp;
    }

    /** Upward speed set at {@code updateEntityAfterFallOn} whose post-gravity discrete arc peaks at {@code targetApex}. */
    public static double reboundForHeight(double targetApex) {
        if (targetApex <= 0) return 0;
        return ArcSolver.vyFor(targetApex) / Ballistic.VERTICAL_DRAG + Ballistic.GRAVITY;
    }

    public static double jump(double jumpSpeed, double rebound) {
        return jumpSpeed + Math.max(0, rebound);
    }

    public static double livingHeight(double rebound) {
        return Ballistic.peak((rebound - Ballistic.GRAVITY) * Ballistic.VERTICAL_DRAG);
    }
}
