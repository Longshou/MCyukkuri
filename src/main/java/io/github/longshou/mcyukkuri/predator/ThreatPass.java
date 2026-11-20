package io.github.longshou.mcyukkuri.predator;

final class ThreatPass {
    static final int ZONE_RADIUS = 10;
    private static final double CLEARANCE = 1.5;

    static boolean inZone(double dx, double dz) {
        return dx * dx + dz * dz <= ZONE_RADIUS * ZONE_RADIUS;
    }

    static double pullUpY(double targetTop) {
        return targetTop + CLEARANCE;
    }

    static boolean canDescend(double feet, double targetTop) {
        return feet > pullUpY(targetTop);
    }

    private ThreatPass() {}
}
