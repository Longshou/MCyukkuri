package io.github.longshou.mcyukkuri.motor.geom;

/** Travel yaw in degrees, independent of the admitted ballistic displacement. */
public final class HopHeading {
    private HopHeading() {}

    public static double yaw(Vec from, Vec to, double facing, double contact) {
        var delta = to.sub(from);
        return delta.hlen() <= contact ? facing : Math.toDegrees(Math.atan2(delta.z(), delta.x())) - 90;
    }

    /** A destination beside or behind the body gets a standing turn before the crouch. */
    public static boolean standingTurn(double error) { return Math.abs(error) >= 90; }

    /** The first flight segment runs on the takeoff tick, using that tick's crouch yaw. */
    public static double launchAllowance(int airTicks, double rate) {
        return Math.min(45, (airTicks - 1) * rate);
    }

    /** Share the turn over the remaining wind-up and flight, keeping takeoff within its allowance. */
    public static double crouchTurn(double error, int crouchTicks, int airTicks, double rate) {
        double angle = Math.abs(error);
        double step = Math.max(angle / (crouchTicks + airTicks - 1),
                (angle - launchAllowance(airTicks, rate)) / crouchTicks);
        return Math.copySign(Math.min(step, rate), error);
    }

    /** Sample one heading per flight segment, from the takeoff pose to the admitted landing pose. */
    public static double flightYaw(double start, double bearing, int segment, int segments) {
        return segment <= 1 ? start : start + Effort.turn(start, bearing, 180) * (segment - 1) / (segments - 1);
    }
}
