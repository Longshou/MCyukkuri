package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoorwayTest {
    @Test void allSidesEnterAndLeaveThroughTheOpening() {
        for (int side : new int[]{-1, 1}) for (double foot : new double[]{.09, .395, .47}) {
            var door = new Doorway(.9125, 1, .625, side);
            for (double[] outside : new double[][]{{-3, 0}, {3, 0}, {0, -3}, {0, 3}}) {
                for (double rest : new double[]{-.2, 0, .2}) {
                    walk(door, foot, outside[0], outside[1], rest, 0);
                    walk(door, foot, rest, 0, outside[0], outside[1]);
                }
            }
        }
    }

    @Test void anOpeningTooNarrowForTheBodyIsNotCrossed() {
        var walls = new Doorway(.9125, 1, .2, 1).walls(100, 0, 0);
        assertTrue(Detour.route(0, 3, 0, 0, .395, walls).shut());
        assertTrue(Detour.route(0, 0, 0, 3, .395, walls).shut());
    }

    @Test void aBabyCanReachASleepingPlaceByTheSideWall() {
        var door = new Doorway(.9125, 1, .5625, 1);
        walk(door, .09, 1.3, -.4, .7915, -.366);
        walk(door, .09, .7915, -.366, 1.3, -.4);
    }

    private static void walk(Doorway door, double foot, double x, double z, double tx, double tz) {
        var walls = door.walls(100, 0, 0);
        for (int tick = 0; tick < 600; tick++) {
            var turn = Detour.route(x, z, tx, tz, foot, walls);
            assertFalse(turn.shut(), "opening must remain reachable");
            double dx = turn.x() - x, dz = turn.z() - z, distance = Math.hypot(dx, dz);
            if (distance > 0) { x += dx / distance * Math.min(.04, distance); z += dz / distance * Math.min(.04, distance); }
            for (var wall : walls) assertTrue(wall.gap(x, z) >= foot - .003, "body crosses the high rim at " + x + "," + z);
            if (Math.hypot(x - tx, z - tz) < .01) return;
        }
        fail("did not finish the crossing");
    }
}
