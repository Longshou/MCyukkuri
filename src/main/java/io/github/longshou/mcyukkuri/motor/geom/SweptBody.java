package io.github.longshou.mcyukkuri.motor.geom;

/** Continuous overlap of two translating body boxes. A shared face alone is clear. */
public final class SweptBody {
    public record Box(Vec min, Vec max) {}

    public static boolean clear(Box body, Vec movement, Box other, Vec otherMovement) {
        var low = other.min.sub(body.max);
        var high = other.max.sub(body.min);
        var step = movement.sub(otherMovement);
        if (low.x() < 0 && low.y() < 0 && low.z() < 0 && high.x() > 0 && high.y() > 0 && high.z() > 0) {
            // Two bodies can already overlap after the other body moved. Let the shorter escape open that gap.
            double near = Math.min(Math.min(-low.x(), high.x()), Math.min(Math.min(-low.y(), high.y()), Math.min(-low.z(), high.z())));
            return escapes(low.x(), high.x(), step.x(), near) || escapes(low.y(), high.y(), step.y(), near)
                    || escapes(low.z(), high.z(), step.z(), near);
        }
        var x = interval(low.x(), high.x(), step.x());
        var y = interval(low.y(), high.y(), step.y());
        var z = interval(low.z(), high.z(), step.z());
        double enter = Math.max(0, Math.max(x.enter, Math.max(y.enter, z.enter)));
        double leave = Math.min(1, Math.min(x.leave, Math.min(y.leave, z.leave)));
        return enter >= leave;
    }

    private static boolean escapes(double low, double high, double step, double near) {
        return step < 0 && -low <= near || step > 0 && high <= near;
    }

    private record Interval(double enter, double leave) {}

    private static Interval interval(double low, double high, double step) {
        if (step > 0) return new Interval(low / step, high / step);
        if (step < 0) return new Interval(high / step, low / step);
        return low < 0 && high > 0 ? new Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                : new Interval(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    private SweptBody() {}
}
