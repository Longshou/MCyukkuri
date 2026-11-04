package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.RadialWall;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
    public static double birthLimit(int count) { return DATA.number("hat_birth." + count + ".growth_max"); }
    public static List<Vec> birthSpots(int count) {
        return DATA.field("hat_birth." + count + ".spots").getAsJsonArray().asList().stream()
                .map(p -> new Vec(p.getAsJsonArray().get(0).getAsDouble(), 0, p.getAsJsonArray().get(1).getAsDouble())).toList();
    }
    public static double birthUnderside(double belly) {
        var keys = DATA.field("hat_birth.underside_by_belly").getAsJsonArray();
        var left = keys.get(0).getAsJsonArray();
        for (int i = 1; i < keys.size(); i++) {
            var right = keys.get(i).getAsJsonArray();
            if (belly >= right.get(0).getAsDouble()) {
                double q = (left.get(0).getAsDouble() - belly) / (left.get(0).getAsDouble() - right.get(0).getAsDouble());
                return left.get(1).getAsDouble() + q * (right.get(1).getAsDouble() - left.get(1).getAsDouble());
            }
            left = right;
        }
        return left.get(1).getAsDouble();
    }
    private static final double YOUNG = DATA.number("seating.young_max_growth"),
            WEIGHT = DATA.number("load.weight_coefficient"), FILL = DATA.number("load.capacity_over_seat");
    public static double youngLimit(double scale) { return YOUNG * scale; }
    public static double weight(double size) { return WEIGHT * size * size * size; }
    public static double capacity(double scale) { return weight(FILL * youngLimit(scale)); }
    /** Sizes of the actual riders other than the captain, wherever they stand. */
    public static double loadRatio(double scale, List<Double> youngSizes) {
        return weightOf(youngSizes) / capacity(scale);
    }
    /** The load ratio once one more body of this size is aboard: what a carer putting a young down weighs against the capacity. */
    public static double loadRatioWith(double scale, List<Double> youngSizes, double newcomer) {
        return (weightOf(youngSizes) + weight(newcomer)) / capacity(scale);
    }
    /** A carer's own choice: the hull takes the young only while its load with that young stays within capacity. */
    public static boolean carries(double scale, List<Double> youngSizes, double newcomer) {
        return loadRatioWith(scale, youngSizes, newcomer) <= 1;
    }
    private static double weightOf(List<Double> youngSizes) { return youngSizes.stream().mapToDouble(HullBounds::weight).sum(); }
    public static List<Vec> brimStops() {
        double r = DATA.number("brim_ring.radius");
        return IntStream.range(0, 72).mapToObj(i -> new Vec(r * Math.cos(i * Math.PI / 36), 0,
                r * Math.sin(i * Math.PI / 36))).toList();
    }

    /** Offer the supported outer ring and inner footing where a larger body's outward edge fits inside the cloth. */
    public static List<Vec> brimStops(String asset, double width, double height, double hullScale, double yaw) {
        var profile = HullProfile.of(HullProfile.BASIN);
        double scale = hullScale / SCALE, unit = profile.measuredUnit();
        var body = BodyFootprint.of(asset, width / scale / unit, height / scale / unit, yaw);
        return brimStops().stream().map(p -> p.scale(1 / unit))
                .flatMap(p -> Stream.of(p, p.scale(Math.min(p.hlen(), RADIUS / unit
                        - FloorSearch.extent(body, p.x() / p.hlen(), p.z() / p.hlen())) / p.hlen())).distinct())
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
