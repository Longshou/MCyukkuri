package io.github.longshou.mcyukkuri.client;

/** One body's projection in one direction in the current render pass. */
final class PartnerProjection {
    int lastPartner = -1;
    private long frame = Long.MIN_VALUE;
    private float partial;
    private double x, z, reach;

    boolean matches(long frame, float partial, double x, double z) {
        return this.frame == frame && this.partial == partial && this.x == x && this.z == z;
    }

    double store(long frame, float partial, double x, double z, double reach) {
        this.frame = frame;
        this.partial = partial;
        this.x = x;
        this.z = z;
        return this.reach = reach;
    }

    double reach() { return reach; }
}
