package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.HullProfile;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A cabin place is one where the rider can turn round (PlacedHatEntity.turnable): admission sweeps the same hair envelope the
 *  executed turn (Floor.plan via PlacedHatEntity.turnSupport) tests heading by heading. */
class HullTurnTest {
    static final double UNIT = HullBounds.UNIT, MARGIN = .02 / UNIT;
    static final HullProfile HULL = HullProfile.of(HullProfile.BASIN);
    static BodyFootprint plain(double size, double yaw) { return BodyFootprint.of("marisa", .8 * size / UNIT, .6 * size / UNIT, Math.toRadians(yaw)); }
    static BodyFootprint hair(double size, double yaw) { return BodyFootprint.envelope("marisa", .8 * size / UNIT, .6 * size / UNIT, Math.toRadians(yaw)); }
    static boolean turnable(double size, double x, double z) { return HULL.turnable(x, z, plain(size, 0), hair(size, 0), MARGIN); }

    @Test void highestSurfaceMatchesASampledSpan() {
        double[] radii = {0, .2, HullBounds.FLOOR_RADIUS, .58, HullBounds.MOUTH, .68, HullBounds.LEVEL_RADIUS, .8, HullBounds.RADIUS};
        for (double lo : radii) for (double hi : radii) if (lo <= hi) {
            double sampled = Double.NEGATIVE_INFINITY;
            for (int i = 0; i <= 400; i++) sampled = Math.max(sampled, HullBounds.surface(lo + (hi - lo) * i / 400));
            assertEquals(sampled, HullBounds.highest(lo, hi), 1e-12, lo + ".." + hi);
        }
    }

    /** The sweep is never lower than the envelope's rest at any of the headings a turn can take (its 5-degree footprints). */
    @Test void sweptRestBoundsEveryHeading() {
        for (double size : new double[]{.1, .18, .227, .3}) for (double x = -6; x <= 6; x += .75) for (double z = -6; z <= 6; z += .75) {
            if (!HULL.inOpening(x, z)) continue;
            double sweep = HULL.turnSupport(x, z, hair(size, 0));
            for (int yaw = 0; yaw < 360; yaw += 5) {
                double rest = HULL.support(x, z, hair(size, yaw));
                if (Double.isFinite(rest)) assertTrue(rest <= sweep + 1e-9, "size=" + size + " at " + x + "," + z + " yaw=" + yaw);
            }
        }
    }

    /** From a turnable place every turn step Floor.plan takes (12 degrees, either way, a whole circle) stays within its rise margin. */
    @Test void aTurnablePlaceTurnsAWholeCircleEitherWay() {
        int places = 0;
        for (double size : new double[]{.1, .18, .227}) for (double x = -6; x <= 6; x += .5) for (double z = -6; z <= 6; z += .5) {
            if (!HULL.inOpening(x, z) || !turnable(size, x, z)) continue;
            places++;
            for (int way : new int[]{-1, 1}) {
                double y = HULL.support(x, z, plain(size, 0));
                for (int yaw = 12; yaw <= 360; yaw += 12) {
                    double next = HULL.support(x, z, hair(size, way * yaw));
                    assertTrue(next - y <= MARGIN, "size=" + size + " at " + x + "," + z + " yaw=" + way * yaw);
                    y = next;
                }
            }
        }
        assertTrue(places > 0);
    }

    /** The middle of the floor is turnable for a young; the pocket by the bow wall where c1 of kid_boat_play was pinned
     *  (`turn refused ... reason=support`, local (0.34, -5.24), size 0.227) is not. */
    @Test void theFloorMiddleTurnsAndTheWallPocketDoesNot() {
        for (double size : new double[]{.1, .18, .227, .3}) assertTrue(turnable(size, 0, 0), "size=" + size);
        assertFalse(turnable(.227, .34, -5.24));
        assertFalse(turnable(.227, -.12, -5.26));
    }
}
