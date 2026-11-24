package io.github.longshou.mcyukkuri.motor.geom;

/** Locomotion rates are in blocks per tick at 20 ticks/s. */
public final class Gait {
    public static final double HUMAN_WALK = 4.317 / 20;
    private static final double FAST_REQUEST = 1.15;
    public static final double GROUND_ATTRIBUTE = .18, FLIGHT_ATTRIBUTE = .30;
    /** Ordinary dry ground: vanilla uses the control value for both acceleration and forward input. */
    static final double GROUND_TRAVEL = .98 / (1 - .60 * Ballistic.AIR_DRAG);
    private static final double FLIGHT_CRUISE = .16;

    private Gait() {}

    /** Fast ground requests (between ordinary 1.0 and retreat 1.2) use the hop-run clip. */
    public static boolean isRunning(boolean crawling, boolean canJump, double requestedModifier, boolean explicitRun) {
        return !crawling && canJump && (fast(requestedModifier) || explicitRun);
    }

    public static boolean fast(double request) { return request >= FAST_REQUEST; }

    /** The ordinary-sized stroke below a fast command's full pace. */
    public static double ordinaryStroke(double fastStroke) { return fastStroke / FAST_REQUEST; }

    /** A cautious or bold body shifts its pace by up to six percent. */
    public static double temperament(int boldness) {
        return .94 + .12 * boldness / 100;
    }

    public static double groundControl(double request, double paceFactor) {
        double pace = request * request * GROUND_TRAVEL * paceFactor;
        return Math.sqrt(ease(pace, HUMAN_WALK * .58, HUMAN_WALK * .86) / GROUND_TRAVEL);
    }

    /** Within the body's radius, turn in place until the next forward step can face the stand. */
    public static boolean needsStandingTurn(double distance, double width, double headingError, double turnRate) {
        return distance <= width / 2 && Math.abs(Math.IEEEremainder(headingError, 360)) > turnRate;
    }

    public static double walk(double pace, double temperament) {
        return ease(pace * temperament, .12, HUMAN_WALK * .82);
    }

    public static double swim(double pace, double temperament) {
        return ease(pace * temperament, .05, HUMAN_WALK * .38);
    }

    public static double flight(double attribute, double request, double growth, double temperament) {
        double pace = FLIGHT_CRUISE * (attribute / FLIGHT_ATTRIBUTE) * request * Math.sqrt(growth) * temperament;
        return ease(pace, FLIGHT_CRUISE, HUMAN_WALK * .98);
    }

    /** Wingbeat period of the {@code predator_fly} clip, seconds. */
    public static final double FLAP_PERIOD = .6;

    /**
     * Height of a flier's body above its glide line, in model blocks of the body's own scale, at {@code seconds} into
     * the wingbeat: lowest where the wing tips are highest (the end of the upstroke, a quarter period in) and rising
     * through the downstroke.
     */
    public static double flapBob(double seconds) {
        return .045 * Math.sin(2 * Math.PI * (seconds / FLAP_PERIOD - .5));
    }

    /** Vertical scale of a spent body's walk: a slow heave about a lowered carriage, one heave per walk cycle. */
    public static double tiredSag(double seconds) {
        return .94 + .015 * Math.sin(2 * Math.PI * seconds / 1.2);
    }

    /** A growing body's reach and its peak horizontal launch pace are separate budgets. */
    public static double hop(double size, double temperament) {
        return HUMAN_WALK * Math.min(.94, (.48 + .36 * size) * temperament);
    }

    /** Keep light requests linear and leave headroom for stronger ones, even above the usual request range. */
    private static double ease(double value, double knee, double ceiling) {
        double excess = value - knee;
        if (excess <= 0) return value;
        double span = ceiling - knee;
        return knee + span * excess / (span + excess);
    }
}
