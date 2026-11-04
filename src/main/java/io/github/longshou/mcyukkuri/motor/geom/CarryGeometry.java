package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.BiPredicate;
import java.util.function.Function;

/** Carry geometry in the parent's model frame: -Z is forward, +X is right. */
public final class CarryGeometry {
    private CarryGeometry() {}
    public static final Vec BODY_LOW = bodyBound("body_min"), BODY_HIGH = bodyBound("body_max");
    private static Vec bodyBound(String key) {
        var a = GeometryJson.read("yukkuri_presentation/braid_rescue.json").field(key).getAsJsonArray();
        return new Vec(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }
    /** How far the across move of a rise-across-descend route reaches into its neighbours (a share of the rise and of the descent). */
    private static final double OVERLAP = 1 / 3.0;
    /**
     * Progress (0..1 each) of the rise, the move across and the descent of one continuous route at {@code t} (0..1 of its
     * time), the rise ending at {@code upEnd} and the descent beginning at {@code downStart}. Each part eases on its own span and
     * the move across begins in the last third of the rise and ends in the first third of the descent, so the body keeps moving
     * through both joins (no stop between the parts) and starts and ends at rest.
     */
    public static double[] phases(double t, double upEnd, double downStart) {
        double across0 = upEnd * (1 - OVERLAP), across1 = downStart + (1 - downStart) * OVERLAP;
        return new double[]{span(t, 0, upEnd), span(t, across0, across1), span(t, downStart, 1)};
    }
    private static double span(double t, double start, double end) { return end <= start ? t >= end ? 1 : 0 : smooth((t - start) / (end - start)); }

    /** One marker-timed route: rise at the grip, pass the front of the body, descend over the destination, in one continuous motion ({@link #phases}). */
    public static Vec transfer(Vec from, Vec to, Vec root, Vec low, Vec high, double radius,
                               double height, double tick, double liftEnd, double overEnd, double place) {
        if (tick <= 0) return from;
        if (tick >= place) return to;
        var k = phases(tick / place, liftEnd / place, overEnd / place);
        double turn = frontTurn(from.x() - root.x(), from.z() - root.z(), to.x() - root.x(), to.z() - root.z());
        var p = swing(root.x(), root.z(), from.x(), from.z(), to.x(), to.z(), turn, k[1]);
        if (height < high.y() && k[1] > 0 && k[1] < 1) {
            double angle = Math.atan2(p.z() - root.z(), p.x() - root.x()), x = Math.cos(angle), z = Math.sin(angle);
            double ax = (low.x() - radius - root.x()) / x, bx = (high.x() + radius - root.x()) / x;
            double az = (low.z() - radius - root.z()) / z, bz = (high.z() + radius - root.z()) / z;
            double near = Math.max(Math.min(ax, bx), Math.min(az, bz)), far = Math.min(Math.max(ax, bx), Math.max(az, bz));
            double r = Math.hypot(p.x() - root.x(), p.z() - root.z());
            if (near <= far && far > 0) r = Math.max(r, far);
            p = new Vec(root.x() + r * x, 0, root.z() + r * z);
        }
        return new Vec(p.x(), from.y() + (height - from.y()) * k[0] + (to.y() - height) * k[2], p.z());
    }
    /** Collision in the carrier's frame, independent of its world yaw. */
    public static boolean clearsBody(Vec feet, double radius, double height) {
        return feet.y() >= BODY_HIGH.y() || feet.y() + height <= BODY_LOW.y()
                || feet.x() + radius <= BODY_LOW.x() || feet.x() - radius >= BODY_HIGH.x()
                || feet.z() + radius <= BODY_LOW.z() || feet.z() - radius >= BODY_HIGH.z();
    }
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


    /** A worn-brim destination still needs a clear landing and swept route. */
    public static Way wayOut(double rootX, double rootZ, Vec from, Vec destination,
                             Function<Vec, Rejection> landing, BiPredicate<Vec, Double> route) {
        var rejected = new int[Rejection.values().length];
        var why = landing.apply(destination);
        if (why == null) {
            double turn = frontTurn(from.x() - rootX, from.z() - rootZ, destination.x() - rootX, destination.z() - rootZ);
            if (!route.test(destination, turn)) why = Rejection.ROUTE;
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

    /** Raise clear of the host, cross above it, then arrive at the mouth: one continuous motion ({@link #phases}) whose parts take
     *  time in proportion to their length. */
    public static Vec liftPoint(Vec from, Vec to, double high, double progress) {
        double length = liftLength(from, to, high);
        if (progress >= 1 || length <= 0) return to;
        if (progress <= 0) return from;
        var k = phases(progress, (high - from.y()) / length, 1 - (high - to.y()) / length);
        return new Vec(from.x() + (to.x() - from.x()) * k[1], from.y() + (high - from.y()) * k[0] + (to.y() - high) * k[2],
                from.z() + (to.z() - from.z()) * k[1]);
    }

    public static int motionTicks(double distance, double pace) { return Math.max(1, (int) Math.ceil(distance / pace)); }
}
