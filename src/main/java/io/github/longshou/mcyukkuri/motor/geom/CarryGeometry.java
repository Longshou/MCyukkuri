package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.DoublePredicate;

/** Carry geometry in the parent's model frame: -Z is forward, +X is right. */
public final class CarryGeometry {
    private CarryGeometry() {}

    /** Ten-degree facing bands prefer visibility; distance decides within a band. */
    public static int compareEndpoints(double ax, double az, double bx, double bz) {
        int angle = Integer.compare(angleBand(ax, az), angleBand(bx, bz));
        return angle != 0 ? angle : Double.compare(ax * ax + az * az, bx * bx + bz * bz);
    }

    private static int angleBand(double x, double z) { return (int) (Math.abs(Math.toDegrees(Math.atan2(x, -z))) / 10); }

    /** Signed turn around the braid root through the forward half-plane; offsets are relative to the root. */
    public static double frontTurn(double fx, double fz, double tx, double tz) {
        double start = Math.atan2(fz, fx), delta = Math.atan2(tz, tx) - start;
        double shortWay = Math.atan2(Math.sin(delta), Math.cos(delta)), other = otherTurn(shortWay);
        if (Math.abs(shortWay) < 1e-6) return 0;
        return Math.sin(start + shortWay / 2) <= Math.sin(start + other / 2) ? shortWay : other;
    }

    public static double otherTurn(double turn) { return turn - Math.copySign(2 * Math.PI, turn); }

    /** Runs the front route's real collision check first; an equal bearing never causes a full loop. */
    public static boolean chooseArc(double front, DoublePredicate clear) {
        return clear.test(front) || front != 0 && clear.test(otherTurn(front));
    }

    /** Live-lip weight: ease in over the final third of the lift and out over the first third of lowering. */
    public static double mouthBlend(double lift, double down) {
        return smooth((lift - 2.0 / 3) * 3) * (1 - smooth(down * 3));
    }

    private static double smooth(double q) { q = Math.clamp(q, 0, 1); return q * q * (3 - 2 * q); }

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
