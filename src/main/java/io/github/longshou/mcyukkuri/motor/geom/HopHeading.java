package io.github.longshou.mcyukkuri.motor.geom;

/** A vertical hop retains its facing; host tilt cannot give it a horizontal turn. */
public final class HopHeading {
    private HopHeading() {}

    public static double yaw(Vec from, Vec to, double facing, double contact) {
        var delta = to.sub(from);
        return delta.hlen() <= contact ? facing : Math.toDegrees(Math.atan2(delta.z(), delta.x())) - 90;
    }
}
