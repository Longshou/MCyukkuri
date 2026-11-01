package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.headwear.HullBounds;

/** Authored hull motion and presentation, shared by server and renderer. */
public final class HullPresentation {
    private static final GeometryJson MOTION = GeometryJson.read("yukkuri_presentation/aquatic_hull.json");

    private HullPresentation() {}
    public static double number(String key) { return MOTION.number(key); }
    public static double wave(long tick, String period) { return Math.sin(tick * 2 * Math.PI / number(period)); }
    public static double bob(long tick) { return number("bob_height") * wave(tick, "bob_period"); }
    public static double waterHeight(double scale, float roll, float pitch) {
        return (HullBounds.WATERLINE * scale) * Math.cos(Math.toRadians(roll)) * Math.cos(Math.toRadians(pitch));
    }
    public static float roll(long tick) { return (float) (number("roll_degrees") * wave(tick, "roll_period")); }
    public static float pitch(long tick) { return (float) (number("pitch_degrees") * wave(tick, "pitch_period")); }
    public static float rock(long tick, double strength) { return (float) (strength * number("shake_degrees") * wave(tick, "rock_period")); }
    public static float turn(float from, float to, double progress) {
        double q = Math.clamp(progress, 0, 1);
        return (float) (from + (to - from) * q * q * (3 - 2 * q));
    }
}
