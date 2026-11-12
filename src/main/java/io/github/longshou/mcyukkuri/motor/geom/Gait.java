package io.github.longshou.mcyukkuri.motor.geom;

/** Locomotion rates are in blocks per tick at 20 ticks/s. */
public final class Gait {
    public static final double HUMAN_WALK = 4.317 / 20;
    public static final double GROUND_ATTRIBUTE = .18, FLIGHT_ATTRIBUTE = .30;
    /** Ordinary dry ground: vanilla uses the control value for both acceleration and forward input. */
    private static final double GROUND_TRAVEL = .98 / (1 - .60 * Ballistic.AIR_DRAG);
    private static final double FLIGHT_CRUISE = .16;

    private Gait() {}

    /** A cautious or bold body shifts its pace by up to six percent. */
    public static double temperament(int boldness) {
        return .94 + .12 * boldness / 100;
    }

    public static double groundControl(double request, double paceFactor) {
        double pace = request * request * GROUND_TRAVEL * paceFactor;
        return Math.sqrt(ease(pace, HUMAN_WALK * .58, HUMAN_WALK * .86) / GROUND_TRAVEL);
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
