package io.github.longshou.mcyukkuri.motor.geom;

/** The shared actions' effort maths: body push shares and turns. No Minecraft types. */
public final class Effort {
    private Effort() {}

    /** The share of a push that moves the other one: the pusher's mass over both (box volumes). */
    public static double share(double pusher, double other) { return pusher + other <= 0 ? 0 : pusher / (pusher + other); }

    /** Fraction of block resistance spent by one whole stroke; no fixed hit count. */
    public static double bash(double power, double resistance) {
        return resistance < 0 || power <= 0 ? 0 : resistance == 0 ? 1 : Math.min(1, power / resistance);
    }

    /** Timid bodies decline; above neutral courage, strength continuously increases willingness. */
    public static double bashChance(double boldness, double power, double resistance) {
        return Math.clamp((boldness - 50) / 50, 0, 1) * Math.min(1, bash(power, resistance) * 4);
    }

    /** The yaw step (degrees) toward {@code heading}, the short way round, at most {@code rate}. */
    public static double turn(double yaw, double heading, double rate) { return Math.clamp(((heading - yaw) % 360 + 540) % 360 - 180, -rate, rate); }

    /** Compare both hull positions with this tick's target; its own motion neither earns nor erases progress. */
    public static double approach(double fromX, double fromZ, double x, double z, double targetX, double targetZ) {
        return Math.hypot(targetX - fromX, targetZ - fromZ) - Math.hypot(targetX - x, targetZ - z);
    }

}
