package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.IntFunction;

/** Ordinary movement stays on the pad or branch until the nestling completes its first flight. */
public final class NestFooting {
    public enum Surface { AIR, PAD, BRANCH, OTHER }
    private NestFooting() {}
    public static int depth(IntFunction<Surface> below, boolean branches) {
        for (int i=0; i<=2; i++) {
            var surface=below.apply(i);
            if (surface==Surface.PAD || branches && surface==Surface.BRANCH) return i;
            if (surface!=Surface.AIR) break;
        }
        return -1;
    }
    public static boolean confines(boolean nestling, boolean passenger, boolean birth, boolean hopping, boolean flying) {
        return nestling && !(passenger || birth || hopping || flying);
    }
    public static double step(double position, double delta, int cell, double sole) {
        double end = Math.clamp(position+delta, cell+sole, cell+1-sole);
        return Math.clamp(end-position, Math.min(0,delta), Math.max(0,delta));
    }
}
