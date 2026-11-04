package io.github.longshou.mcyukkuri.motor.geom;

/** Nearest fully supported sole on a unit bank cell's face, whose normal points toward the water. */
public final class BankStand {
    public static final double SUPPORT_FRACTION = .42;
    private BankStand() {}

    /** A clear body on the water side and its supported sole on the bank side share the same hop budget. */
    public static double widthLimit(double reach) { return reach / (.5 + SUPPORT_FRACTION); }

    public static Vec nearest(Vec edge, Vec normal, double radius, Vec from) {
        var tangent = new Vec(-normal.z(), 0, normal.x());
        var delta = from.sub(edge);
        double along = Math.clamp(delta.x() * tangent.x() + delta.z() * tangent.z(), radius - .5, .5 - radius);
        return edge.sub(normal.scale(radius)).add(tangent.scale(along));
    }
}
