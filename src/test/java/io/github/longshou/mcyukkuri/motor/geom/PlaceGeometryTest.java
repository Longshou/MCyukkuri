package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.HullProfile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaceGeometryTest {
    private static final double UNIT = HullBounds.UNIT;
    private static final HullProfile HULL = HullProfile.of(HullProfile.BASIN);
    private static final List<FloorSearch.Placed> CAPTAIN = List.of(-15, 0, 15).stream()
            .map(yaw -> new FloorSearch.Placed(BodyFootprint.envelope("marisa", .8 / UNIT, .6 / UNIT, Math.toRadians(yaw)),
                    HullBounds.HOME.scale(1 / UNIT))).toList();
    private static Disc hat(double size, double x, double z) {
        var body = BodyFootprint.of("marisa", .8 * size / UNIT, .6 * size / UNIT, 0);
        double feet = HULL.support(x / UNIT, z / UNIT, body);
        double bottom = feet + 10.3774 * size / 16 / UNIT;
        return new Disc(x / UNIT, z / UNIT, 14.18 * size / 16 / UNIT, bottom, bottom + .3 * size / 16 / UNIT);
    }
    private static boolean fits(Disc hat, List<Disc> others) {
        return PlaceGeometry.cabin(hat, HullBounds.innerRadius(hat.bottom()*UNIT)/UNIT, CAPTAIN, others, .3 / 1.4);
    }
    private static List<Disc> places(double size) {
        var out = new ArrayList<Disc>();
        for (double x = -.5; x <= .5; x += .015625) for (double z = -.6; z <= 0; z += .015625) {
            var disc = hat(size, x, z);
            if (disc.bottom() - 10.3774*size/16/UNIT > HULL.floor() + .02/UNIT) continue;
            if (fits(disc, List.of())) out.add(disc);
        }
        return out;
    }
    @Test void cabinFitsOneLargerBabyOrTwoSmallBabiesButNotAnOversizeHat() {
        assertFalse(places(.18).isEmpty(), "one .18 baby");
        var pair = places(.15);
        assertTrue(pair.stream().anyMatch(a -> pair.stream().anyMatch(b -> fits(b, List.of(a)))), "two .15 babies");
        assertTrue(places(.20).isEmpty(), ".20 hat intersects captain or wall");
    }
    @Test void headCapSupportsYoungAcrossJoinedCellsButNeverAcrossItsEdge() {
        var head = GeometryJson.read("yukkuri_presentation/hat_interactions.json").field("variants.marisa.head").getAsJsonObject();
        var supports = new ArrayList<double[]>();
        for (var value : head.getAsJsonArray("supports")) {
            var cell = value.getAsJsonArray();
            supports.add(new double[]{cell.get(0).getAsDouble(), cell.get(1).getAsDouble(), cell.get(2).getAsDouble(),
                    cell.get(3).getAsDouble(), cell.get(4).getAsDouble()});
        }
        double margin = head.get("margin").getAsDouble();
        for (double size : new double[]{.15, .20, .45})
            assertTrue(Double.isFinite(PlaceGeometry.head(supports, 0, 0, 6.4 * size, margin)), "head support size=" + size);
        assertTrue(Double.isNaN(PlaceGeometry.head(supports, 5, 0, 6.4 * .2, margin)));
    }
}
