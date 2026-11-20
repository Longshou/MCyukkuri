package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.pose.WallYield;

/** One body's projection in one direction in the current render pass. */
final class PartnerProjection {
    int lastPartner = -1;
    private long frame = Long.MIN_VALUE;
    private float partial;
    private double x, z, reach;
    private String asset;
    private int headwear, flags;
    private boolean accessory;
    private double scale;
    private WallYield.Bounds loose;

    WallYield.Bounds bounds(YukkuriView actor) {
        String id = actor.assetId();
        double size = (double)actor.getScale() * actor.growthScale();
        if (!id.equals(asset) || scale != size || flags != actor.subspeciesFlags()
                || headwear != actor.visibleHeadwear() || accessory != actor.hasVisibleAccessory()) {
            asset = id;
            scale = size;
            flags = actor.subspeciesFlags();
            headwear = actor.visibleHeadwear();
            accessory = actor.hasVisibleAccessory();
            loose = WallClearance.bounds(actor, YukkuriModel.get(id));
        }
        return loose;
    }

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
