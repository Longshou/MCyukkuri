package io.github.longshou.mcyukkuri.motor.geom;

/** The shared actions' effort maths: body push shares, paddle strokes and turns. No Minecraft types. */
public final class Effort {
    private Effort() {}

    /** The share of a push that moves the other one: the pusher's mass over both (box volumes). */
    public static double share(double pusher, double other) { return pusher + other <= 0 ? 0 : pusher / (pusher + other); }

    /** 1 inside the power stroke {@code [start, end)} of a {@code length}-tick clip cycle, else 0. */
    public static double stroke(long tick, int start, int end, int length) {
        long t = Math.floorMod(tick, Math.max(1, length));
        return t >= start && t < end ? 1 : 0;
    }

    /** The yaw step (degrees) toward {@code heading}, the short way round, at most {@code rate}. */
    public static double turn(double yaw, double heading, double rate) { return Math.clamp(((heading - yaw) % 360 + 540) % 360 - 180, -rate, rate); }

}
