package io.github.longshou.mcyukkuri.motor.geom;

/** Nominal clear-floor stands shared by gameplay and the pose review. Distances are in blocks. */
public final class ContactStand {
    public static final double MOUTH_CLEARANCE = .10;

    private ContactStand() {}

    public static double feeding(double width, double peerWidth) {
        return (width + peerWidth) * .5 + MOUTH_CLEARANCE - MOUTH_CLEARANCE * .5;
    }

    public static double courtRear(double width, double peerWidth) {
        return (width + peerWidth) * .5 * .9;
    }

    /** Tangency of the square collision footprints along the given horizontal bearing. */
    public static double tangent(double width, double peerWidth, double x, double z) {
        return (width + peerWidth) * .5 / Math.max(Math.abs(x), Math.abs(z));
    }
}
