package io.github.longshou.mcyukkuri.predator;

/** The founder's last observation. The block owns the twig count and identifies its one builder. */
record FoundingFrame(String dimension, long pos, int twigs, long since) {
    FoundingFrame observed(int count) {
        return count == twigs ? this : new FoundingFrame(dimension, pos, count, since);
    }

    /** On the same 0–100 scale as hunger, work starts halfway up and closes half the remaining gap
     * after one ordinary adult rest interval (1200 ticks). Idle activities have zero pressure. */
    double pressure(long now, boolean homeless, boolean needsAttention) {
        if (!homeless || needsAttention) return 0;
        double age = Math.max(0, now - since);
        return 100 - 50 / (1 + age / 1200);
    }

    static boolean chooseWork(double pressure, double nutrition, boolean foodAvailable) {
        return pressure > (foodAvailable ? 100 - nutrition : 0);
    }
}
