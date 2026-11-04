package io.github.longshou.mcyukkuri.motor.geom;

/** Nearest body centre inside a unit bank cell, whose face normal points toward the water. */
public final class BankStand {
    private BankStand() {}

    public static Vec nearest(Vec edge, Vec normal, double contact, Vec from) {
        var centre = edge.sub(normal.scale(.5));
        return new Vec(Math.clamp(from.x(), centre.x() - .5 + contact, centre.x() + .5 - contact), edge.y(),
                Math.clamp(from.z(), centre.z() - .5 + contact, centre.z() + .5 - contact));
    }
}
