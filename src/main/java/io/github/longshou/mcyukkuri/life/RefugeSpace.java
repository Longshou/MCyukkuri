package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.motor.geom.SweptBody;
import io.github.longshou.mcyukkuri.motor.geom.SweptBody.Box;
import io.github.longshou.mcyukkuri.motor.geom.Vec;

/** The volume of a resting body, independent of whether a hazard has a collision shape. */
final class RefugeSpace {
    interface Hazard { boolean at(int x, int y, int z); }

    static boolean touchesHazard(Box body, Hazard hazard) {
        int xEnd = (int)Math.ceil(body.max().x()), yEnd = (int)Math.ceil(body.max().y()), zEnd = (int)Math.ceil(body.max().z());
        for (int x = (int)Math.floor(body.min().x()); x < xEnd; x++) {
            for (int y = (int)Math.floor(body.min().y()); y < yEnd; y++) {
                for (int z = (int)Math.floor(body.min().z()); z < zEnd; z++) {
                    if (hazard.at(x, y, z)) return true;
                }
            }
        }
        return false;
    }

    static boolean blocksStand(Box arriving, Box other, boolean resting) {
        return resting && !SweptBody.clear(arriving, Vec.ZERO, other, Vec.ZERO);
    }

    private RefugeSpace() {}
}
