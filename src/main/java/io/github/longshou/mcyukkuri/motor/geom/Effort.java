package io.github.longshou.mcyukkuri.motor.geom;

/** The water actions' effort maths (refactor WP5): push shares, paddle strokes and turns, righting. No Minecraft types. */
public final class Effort {
    private Effort() {}

    /** The share of a push that moves the other one: the pusher's mass over both (box volumes, spec 9.4). */
    public static double share(double pusher, double other) { return pusher + other <= 0 ? 0 : pusher / (pusher + other); }

    /** 1 inside the power stroke {@code [start, end)} of a {@code length}-tick clip cycle, else 0. */
    public static double stroke(long tick, int start, int end, int length) {
        long t = Math.floorMod(tick, Math.max(1, length));
        return t >= start && t < end ? 1 : 0;
    }

    /** What a stroke tick adds to a hull keeping {@code drag} of its speed a tick: steady strokes settle at pace · share. */
    public static double drive(double pace, double share, double drag) { return drag <= 0 ? 0 : pace * share * (1 - drag) / drag; }

    /** The yaw step (degrees) toward {@code heading}, the short way round, at most {@code rate}. */
    public static double turn(double yaw, double heading, double rate) { return Math.clamp(((heading - yaw) % 360 + 540) % 360 - 180, -rate, rate); }

    /** One push tick's share of turning a hull over: its brim travels half a round of {@code reach} at pace · share. */
    public static double righting(double pace, double share, double reach) { return reach <= 0 ? 0 : pace * share / (Math.PI * reach); }
}
