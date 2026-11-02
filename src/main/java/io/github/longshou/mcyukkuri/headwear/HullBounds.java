package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
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

    public static List<Vec> brimStops(double growth) {
        return IntStream.range(0, 72).mapToObj(i -> brim(growth, i * Math.PI / 36)).toList();
    }

    /** A young's underside on the authored brim ring, in blocks at captain growth one. */
    public static Vec brim(double growth, double bearing) {
        var heights = DATA.field("brim_ring.underside_y").getAsJsonArray();
        var a = heights.get(0).getAsJsonArray();
        var b = heights.get(1).getAsJsonArray();
        double t = (growth - a.get(0).getAsDouble()) / (b.get(0).getAsDouble() - a.get(0).getAsDouble());
        double y = a.get(1).getAsDouble() + t * (b.get(1).getAsDouble() - a.get(1).getAsDouble());
        double r = DATA.number("brim_ring.radius");
        return new Vec(r * Math.cos(bearing), y, r * Math.sin(bearing));
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
