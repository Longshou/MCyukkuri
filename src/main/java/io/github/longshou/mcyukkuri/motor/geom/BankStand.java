package io.github.longshou.mcyukkuri.motor.geom;

/** Nearest body centre inside a unit bank cell, whose face normal points toward the water. */
public final class BankStand {
    private BankStand() {}

    public static Vec nearest(Vec edge, Vec normal, Vec from) {
        var centre = edge.sub(normal.scale(.5));
        return new Vec(Math.clamp(from.x(), Math.nextUp(centre.x() - .5), Math.nextDown(centre.x() + .5)), edge.y(),
                Math.clamp(from.z(), Math.nextUp(centre.z() - .5), Math.nextDown(centre.z() + .5)));
    }
}
