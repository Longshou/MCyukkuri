package io.github.longshou.mcyukkuri.motor.geom;

/** Carry geometry in the parent's model frame: -Z is forward, +X is right. */
public final class CarryGeometry {
    private CarryGeometry() {}

    /** Visible endpoints first, then the nearer point at the same bearing. */
    public static int compareEndpoints(double ax, double az, double bx, double bz) {
        int angle = Double.compare(Math.abs(Math.atan2(ax, -az)), Math.abs(Math.atan2(bx, -bz)));
        return angle != 0 ? angle : Double.compare(ax * ax + az * az, bx * bx + bz * bz);
    }

    /** Signed turn around the braid root through the forward half-plane; offsets are relative to the root. */
    public static double frontTurn(double fx, double fz, double tx, double tz) {
        double start = Math.atan2(fz, fx), delta = Math.atan2(tz, tx) - start;
        double shortWay = Math.atan2(Math.sin(delta), Math.cos(delta)), other = otherTurn(shortWay);
        return Math.sin(start + shortWay / 2) <= Math.sin(start + other / 2) ? shortWay : other;
    }

    public static double otherTurn(double turn) { return turn - Math.copySign(2 * Math.PI, turn); }

    /** Radial sweep, preserving both endpoints while interpolating their distances from the root. */
    public static Vec swing(double rx, double rz, double fx, double fz, double tx, double tz, double turn, double q) {
        double angle = Math.atan2(fz - rz, fx - rx) + turn * q;
        double radius = (1 - q) * Math.hypot(fx - rx, fz - rz) + q * Math.hypot(tx - rx, tz - rz);
        return new Vec(rx + radius * Math.cos(angle), 0, rz + radius * Math.sin(angle));
    }

    public static double liftLength(Vec from, Vec to, double high) {
        return high - from.y() + to.sub(from).hlen() + high - to.y();
    }

    /** Raise clear of the host, cross above it, then arrive at the mouth, all at the same physical pace. */
    public static Vec liftPoint(Vec from, Vec to, double high, double progress) {
        double up = high - from.y(), across = to.sub(from).hlen();
        double distance = liftLength(from, to, high) * progress;
        if (distance < up) return new Vec(from.x(), from.y() + distance, from.z());
        distance -= up;
        if (distance < across) return new Vec(from.x() + (to.x() - from.x()) * distance / across, high,
                from.z() + (to.z() - from.z()) * distance / across);
        return new Vec(to.x(), high - (distance - across), to.z());
    }

    public static int motionTicks(double distance, double pace) { return Math.max(1, (int) Math.ceil(distance / pace)); }
}
