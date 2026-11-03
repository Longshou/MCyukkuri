package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.BiPredicate;
import java.util.function.DoublePredicate;
import java.util.function.Function;

/** Carry geometry in the parent's model frame: -Z is forward, +X is right. */
public final class CarryGeometry {
    private CarryGeometry() {}
    /** Leave the brim radially, swing outside its edge at height, then lower vertically beside the body. */
    public static Vec brimRelease(Vec from, Vec to, double clearance, double height, double progress) {
        double radius = Math.max(from.hlen() + clearance, to.hlen());
        double start = Math.atan2(from.z(), from.x());
        var edge = new Vec(Math.cos(start) * radius, height, Math.sin(start) * radius);
        if (progress < .35) return mix(from, edge, smooth(progress / .35));
        double end = Math.atan2(to.z(), to.x());
        double turn = Math.atan2(Math.sin(end - start), Math.cos(end - start));
        if (progress < .7) {
            double q = smooth((progress - .35) / .35), angle = start + turn * q;
            double r = radius + (to.hlen() - radius) * q;
            return new Vec(Math.cos(angle) * r, height, Math.sin(angle) * r);
        }
        return mix(new Vec(to.x(), height, to.z()), to, smooth((progress - .7) / .3));
    }
    private static Vec mix(Vec a, Vec b, double q) { return new Vec(a.x() + (b.x() - a.x()) * q, a.y() + (b.y() - a.y()) * q, a.z() + (b.z() - a.z()) * q); }
    public enum Rejection { TERRAIN, ROUTE }
    public record Way(Vec point, int[] rejected) {}

    public static double reachGap(Vec root, Vec low, Vec high, double length) {
        var gap = root.sub(new Vec(Math.clamp(root.x(), low.x(), high.x()), Math.clamp(root.y(), low.y(), high.y()),
                Math.clamp(root.z(), low.z(), high.z())));
        return Math.sqrt(gap.x() * gap.x() + gap.y() * gap.y() + gap.z() * gap.z()) - length;
    }

    /** Release converges on the supported feet, even when the host pins them above the unpinned endpoint. */
    public static double releaseRemaining(Vec feet, Vec endpoint, double supportedY) {
        return Math.sqrt(Math.pow(feet.x() - endpoint.x(), 2) + Math.pow(feet.y() - supportedY, 2) + Math.pow(feet.z() - endpoint.z(), 2));
    }

    /** Dry braid hold above the water or supporting hull surface, in world coordinates. */
    public static double liftHeight(double feet, double floor, double surface, double height) {
        if (surface > floor) floor = surface;
        return Math.max(feet, floor + height);
    }

    /** Ease into and out of lowering without exceeding the original pace after a destination moves. */
    public static Vec lowering(Vec from, Vec to, double pace, double ticks) {
        var delta = to.sub(from);
        double distance = Math.sqrt(delta.x() * delta.x() + delta.y() * delta.y() + delta.z() * delta.z());
        int duration = loweringTicks(distance, pace);
        return ticks >= duration ? to : mix(from, to, smooth(ticks / duration));
    }
    /** Smoothstep's peak derivative is 3/2, so this duration keeps every step within the admitted pace. */
    public static int loweringTicks(double distance, double pace) { return motionTicks(1.5 * distance, pace); }

    /** A worn-brim destination still needs a clear landing and swept route. */
    public static Way wayOut(double rootX, double rootZ, Vec from, Vec destination,
                             Function<Vec, Rejection> landing, BiPredicate<Vec, Double> route) {
        var rejected = new int[Rejection.values().length];
        var why = landing.apply(destination);
        if (why == null) {
            double turn = frontTurn(from.x() - rootX, from.z() - rootZ, destination.x() - rootX, destination.z() - rootZ);
            if (!chooseArc(turn, arc -> route.test(destination, arc))) why = Rejection.ROUTE;
        }
        if (why == null) return new Way(destination, rejected);
        rejected[why.ordinal()]++;
        return new Way(null, rejected);
    }

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
