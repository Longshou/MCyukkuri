package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;

/** Authored hull dimensions (blocks at the exported scale) and presentation, shared by server and renderer. */
public final class HullPresentation {
    private static final GeometryJson BOUNDS = GeometryJson.read("yukkuri_presentation/hull_bounds/aquatic_basin_hat_turned.json");
    private static final GeometryJson MOTION = GeometryJson.read("yukkuri_presentation/aquatic_hull.json");
    public static final double SCALE = BOUNDS.number("scale"), WATERLINE = BOUNDS.number("waterline_y"),
            BRIM = BOUNDS.number("brim.y"), RADIUS = BOUNDS.number("brim.outer_radius"),
            MOUTH = BOUNDS.number("mouth.radius"), FLOOR = BOUNDS.number("floor.y"), KEEL = BOUNDS.number("keel_y");

    private HullPresentation() {}
    public static double number(String key) { return MOTION.number(key); }
    public static double wave(long tick, String period) { return Math.sin(tick * 2 * Math.PI / number(period)); }
    public static double bob(long tick) { return number("bob_height") * wave(tick, "bob_period"); }
    public static float roll(long tick) { return (float) (number("roll_degrees") * wave(tick, "roll_period")); }
    public static float pitch(long tick) { return (float) (number("pitch_degrees") * wave(tick, "pitch_period")); }
}
