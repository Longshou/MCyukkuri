package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.RadialWall;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.List;
import java.util.stream.IntStream;

/** The round hull's authored dimensions, in blocks at its exported scale; origin at the brim. */
public final class HullBounds {
    public static final GeometryJson DATA = GeometryJson.read("yukkuri_presentation/hull_bounds/aquatic_basin_hat_turned.json");
    public static final double SCALE = DATA.number("scale"), UNIT = SCALE / 16,
            BOARDING_YAW = DATA.number("boarding_yaw"), WATERLINE = DATA.number("waterline_y"), KEEL = DATA.number("keel_y"),
            MOUTH = DATA.number("mouth.radius"), FLOOR_RADIUS = DATA.number("floor.radius"), FLOOR = DATA.number("floor.y"),
            BRIM = DATA.number("brim.y"), LEVEL_RADIUS = DATA.number("brim.level_radius"),
            RADIUS = DATA.number("brim.outer_radius"), EDGE = DATA.number("brim.edge_y");

    private HullBounds() {}

    private static final RadialWall WALL = RadialWall.read("yukkuri_props/aquatic_basin_hat_turned.json", "hull");
    public static double innerRadius(double height) { return WALL.radius(Math.min(height, BRIM) / UNIT) * UNIT; }

    public static final Vec HOME = new Vec(DATA.number("captain_seat.x"), DATA.number("captain_seat.y"), DATA.number("captain_seat.z"));
    private static final double YOUNG = DATA.number("seating.young_max_growth"),
            WEIGHT = DATA.number("load.weight_coefficient"), FILL = DATA.number("load.capacity_over_seat");
    public static double youngLimit(double scale) { return YOUNG * scale; }
    public static double weight(double size) { return WEIGHT * size * size * size; }
    public static double capacity(double scale) { return weight(FILL * youngLimit(scale)); }
    /** Sizes of the actual riders other than the captain, wherever they stand. */
    public static double loadRatio(double scale, List<Double> youngSizes) {
        return youngSizes.stream().mapToDouble(HullBounds::weight).sum() / capacity(scale);
    }
    public static List<Vec> brimStops() {
        double r = DATA.number("brim_ring.radius");
        return IntStream.range(0, 72).mapToObj(i -> new Vec(r * Math.cos(i * Math.PI / 36), 0,
                r * Math.sin(i * Math.PI / 36))).toList();
    }

    /** Keep the preferred brim ring where the body fits; bring a larger body's outward edge inside the cloth. */
    public static List<Vec> brimStops(String asset, double width, double height, double hullScale, double yaw) {
        var profile = HullProfile.of(HullProfile.BASIN);
        double scale = hullScale / SCALE, unit = profile.measuredUnit();
        var body = BodyFootprint.of(asset, width / scale / unit, height / scale / unit, yaw);
        return brimStops().stream().map(p -> p.scale(1 / unit))
                .map(p -> p.scale(Math.min(p.hlen(), RADIUS / unit
                        - FloorSearch.extent(body, p.x() / p.hlen(), p.z() / p.hlen())) / p.hlen()))
                .map(p -> new Vec(p.x(), profile.support(p.x(), p.z(), body), p.z())).toList();
    }

    /** Basin floor, inner wall and the level-to-drooping brim, all from the bounds contract. */
    public static double surface(double radius) {
        if (radius > RADIUS) return Double.NaN;
        if (radius <= FLOOR_RADIUS) return FLOOR;
        if (radius < MOUTH) return FLOOR + (BRIM - FLOOR) * (radius - FLOOR_RADIUS) / (MOUTH - FLOOR_RADIUS);
        if (radius <= LEVEL_RADIUS) return BRIM;
        return BRIM + (EDGE - BRIM) * (radius - LEVEL_RADIUS) / (RADIUS - LEVEL_RADIUS);
    }
}
