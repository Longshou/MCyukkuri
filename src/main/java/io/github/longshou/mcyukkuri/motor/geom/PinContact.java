package io.github.longshou.mcyukkuri.motor.geom;

/** Ground contact behind a peer: a lateral step can preserve contact without matching one exact rear point. */
public final class PinContact {
    private PinContact() {}

    public static boolean touches(Vec offset, Vec forward, double width, double peerWidth) {
        double half = (width + peerWidth) * .5;
        double x = Math.abs(offset.x()), z = Math.abs(offset.z());
        // Collision-box tangency is the geometry limit; tolerance only allows a small visible contact gap.
        double touch = Math.min(width, peerWidth) * .25;
        return offset.x() * forward.x() + offset.z() * forward.z() <= 0
                && (x >= half - .001 || z >= half - .001) // collision sweep rounding, not visible overlap
                && Math.hypot(Math.max(0, x - half), Math.max(0, z - half)) <= touch
                && Math.abs(offset.y()) <= .05; // shared Hop ground-contact tolerance
    }
}
