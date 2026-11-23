package io.github.longshou.mcyukkuri.perception;

/** How far a sound carries to one body: an interval around the cue's loudness, shifted by stats and state, halved through each wall. */
public final class Hearing {
    /** Wall layers beyond this add nothing: a thick wall is as good as three. */
    public static final int MAX_LAYERS = 3;
    private static final double TIMID = 1.15, BOLD = .9, SALIENT = 1.25, ASLEEP = .4, PANIC = .6;

    private Hearing() {}

    /** Blocks the sound reaches this listener; {@code layers} is the number of solid cells between source and ear. */
    public static double radius(double loudness, int boldness, boolean salient, boolean asleep, boolean panicking, int layers) {
        double ear = BOLD + (TIMID - BOLD) * (1 - Sight.ramp(boldness, 20, 80));
        return loudness * ear * (salient ? SALIENT : 1) * (asleep ? ASLEEP : 1) * (panicking ? PANIC : 1)
                * Math.pow(.5, Math.min(Math.max(layers, 0), MAX_LAYERS));
    }

    public static boolean audible(double radius, double distanceSq) {
        return distanceSq <= radius * radius;
    }
}
