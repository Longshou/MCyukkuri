package io.github.longshou.mcyukkuri.aquatic;

import java.util.ArrayDeque;
import java.util.List;

/** Explicit capsize episodes; presentation, passengers and stores are not inputs. No Minecraft types. */
public final class HullTriggers {
    public static final int SHAKES = (int) HullPresentation.number("shake_count"),
            WINDOW = (int) HullPresentation.number("shake_window"), WET_TICKS = (int) HullPresentation.number("turbulence_ticks");
    public static final double ANGLE = HullPresentation.number("turbulence_angle"), IMPACT = HullPresentation.number("impact_speed");
    public record Flow(double x, double z, boolean falling) {}
    public record Water(boolean falling, double angle) {
        public boolean turbulent() { return falling || angle > ANGLE; }
    }
    private final ArrayDeque<Long> shakes = new ArrayDeque<>();
    private int wetTicks;
    private boolean wetFired, impacting;

    /** Excess as a fraction of the actual load: 0 at capacity, 1/2 at twice capacity, approaching 1. */
    public static double overloadChance(double ratio) { return ratio <= 1 ? 0 : 1 - 1 / ratio; }

    /** N clicks in any sliding window fire once and consume that group. */
    public int shake(long tick) {
        while (!shakes.isEmpty() && tick - shakes.getFirst() > WINDOW) shakes.removeFirst();
        shakes.addLast(tick);
        int count = shakes.size();
        if (count == SHAKES) shakes.clear();
        return count;
    }

    /** Only the four neighbours' nonzero horizontal directions are compared. */
    public static Water classify(boolean falling, List<Flow> neighbours) {
        double angle = 0;
        for (int i = 0; i < neighbours.size(); i++) {
            var a = neighbours.get(i);
            falling |= a.falling();
            for (int j = 0; j < i; j++) {
                var b = neighbours.get(j);
                double lengths = Math.hypot(a.x(), a.z()) * Math.hypot(b.x(), b.z());
                if (lengths > 0) angle = Math.max(angle, Math.toDegrees(Math.acos(Math.clamp((a.x() * b.x() + a.z() * b.z()) / lengths, -1, 1))));
            }
        }
        return new Water(falling, angle);
    }

    public boolean water(Water sample) {
        if (!sample.turbulent()) { wetTicks = 0; wetFired = false; return false; }
        wetTicks++;
        if (wetFired || wetTicks < WET_TICKS) return false;
        wetFired = true;
        return true;
    }

    /** Approach along the contact normal, never a total speed or an overlap depth. */
    public boolean impact(double approach) {
        boolean strong = approach >= IMPACT, fire = strong && !impacting;
        impacting = strong;
        return fire;
    }
}
