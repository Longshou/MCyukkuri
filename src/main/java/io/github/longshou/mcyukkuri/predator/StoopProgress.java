package io.github.longshou.mcyukkuri.predator;

/** Elapsed ticks since the predator last gained a mouth-reach on its moving contact point. */
final class StoopProgress {
    private static final int STALL_TICKS = 60;
    private double gapAtGain;
    private int lastGainTick;

    boolean stalled(int passTick, double gap, double mouthReach) {
        // Count a new mouth-reach of net gain. Circling back to the same gap cannot renew the pass.
        if (passTick == 1 || gapAtGain - gap >= mouthReach) {
            gapAtGain = gap;
            lastGainTick = passTick;
        }
        return passTick - lastGainTick >= STALL_TICKS;
    }
}
