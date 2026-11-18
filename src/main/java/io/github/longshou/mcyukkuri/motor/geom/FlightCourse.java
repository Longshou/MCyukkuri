package io.github.longshou.mcyukkuri.motor.geom;

/** Horizontal route checks, independent of the floor and navigation lookup. */
public final class FlightCourse {
    public record Direction(double x, double z) {
        public double dot(Direction other) { return x * other.x + z * other.z; }
        public Direction rotate(double degrees) {
            double angle = Math.toRadians(degrees), cos = Math.cos(angle), sin = Math.sin(angle);
            return new Direction(x * cos - z * sin, x * sin + z * cos);
        }
    }

    public static Direction awayUnit(double x, double z, double threatX, double threatZ, double yaw) {
        double dx = x - threatX, dz = z - threatZ, length = Math.hypot(dx, dz);
        return length == 0 ? new Direction(-Math.sin(yaw), Math.cos(yaw)) : new Direction(dx / length, dz / length);
    }

    public static boolean passesNearThreat(double x, double z, double endX, double endZ,
                                           double threatX, double threatZ, double clearance) {
        double start = Math.hypot(x - threatX, z - threatZ);
        if (Math.hypot(endX - threatX, endZ - threatZ) <= start + .5) return true;
        return segmentDistance(x, z, endX, endZ, threatX, threatZ) < Math.min(clearance, start - .35);
    }

    public static double segmentDistance(double x, double z, double endX, double endZ, double threatX, double threatZ) {
        double dx = endX - x, dz = endZ - z;
        if (dx == 0 && dz == 0) return Math.hypot(x - threatX, z - threatZ);
        double t = Math.clamp(((threatX - x) * dx + (threatZ - z) * dz) / (dx * dx + dz * dz), 0, 1);
        return Math.hypot(x + t * dx - threatX, z + t * dz - threatZ);
    }

    public static double scoreCandidate(double x, double z, double endX, double endZ, double threatX, double threatZ,
                                        Direction committed) {
        if (passesNearThreat(x, z, endX, endZ, threatX, threatZ, 3.8)) return Double.NEGATIVE_INFINITY;
        Direction step = awayUnit(endX, endZ, x, z, 0);
        double gain = Math.hypot(endX - threatX, endZ - threatZ) - Math.hypot(x - threatX, z - threatZ);
        return .55 * step.dot(committed) + .30 * step.dot(awayUnit(x, z, threatX, threatZ, 0))
                + .15 * Math.clamp(gain / 8, 0, 1);
    }

    public static boolean shouldReplan(long age, boolean arrived, boolean stalled, Direction committed, Direction away) {
        return age >= 40 || arrived || stalled || committed.dot(away) < .25;
    }

    private FlightCourse() {}
}
