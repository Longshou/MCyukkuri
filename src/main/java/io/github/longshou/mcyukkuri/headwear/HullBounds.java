package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;

/** The round hull's authored dimensions, in blocks at its exported scale; origin at the brim. */
public final class HullBounds {
    public static final GeometryJson DATA = GeometryJson.read("yukkuri_presentation/hull_bounds/aquatic_basin_hat_turned.json");
    public static final double SCALE = DATA.number("scale"), UNIT = SCALE / 16,
            BOARDING_YAW = DATA.number("boarding_yaw"), WATERLINE = DATA.number("waterline_y"), KEEL = DATA.number("keel_y"),
            MOUTH = DATA.number("mouth.radius"), FLOOR_RADIUS = DATA.number("floor.radius"), FLOOR = DATA.number("floor.y"),
            BRIM = DATA.number("brim.y"), LEVEL_RADIUS = DATA.number("brim.level_radius"),
            RADIUS = DATA.number("brim.outer_radius"), EDGE = DATA.number("brim.edge_y");

    private HullBounds() {}

    /** Basin floor, inner wall and the level-to-drooping brim, all from the bounds contract. */
    public static double surface(double radius) {
        if (radius > RADIUS) return Double.NaN;
        if (radius <= FLOOR_RADIUS) return FLOOR;
        if (radius < MOUTH) return FLOOR + (BRIM - FLOOR) * (radius - FLOOR_RADIUS) / (MOUTH - FLOOR_RADIUS);
        if (radius <= LEVEL_RADIUS) return BRIM;
        return BRIM + (EDGE - BRIM) * (radius - LEVEL_RADIUS) / (RADIUS - LEVEL_RADIUS);
    }
}
