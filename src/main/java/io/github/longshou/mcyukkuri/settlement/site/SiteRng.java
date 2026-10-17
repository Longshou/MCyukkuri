package io.github.longshou.mcyukkuri.settlement.site;

/** Tiny deterministic generator (splitmix64) so that planner, builder and the python preview mirror agree exactly. */
public final class SiteRng {
    private long state;
    public SiteRng(long seed) { state = seed; }
    public long next() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
    /** Uniform in [0, bound). */
    public int nextInt(int bound) { return (int)((next() >>> 33) % bound); }
    /** Uniform in [0, 1). */
    public double nextDouble() { return (next() >>> 11) * (1.0 / 9007199254740992.0); }
    public double range(double low, double high) { return low + (high - low) * nextDouble(); }
    /** Position-keyed value in [0, 1), independent of evaluation order. */
    public static double hash(long seed, int x, int z, int salt) {
        long h = seed ^ (x * 0x632BE59BD9B4E019L) ^ (z * 0x9E3779B97F4A7C15L) ^ (salt * 0xD1B54A32D192ED03L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * (1.0 / 9007199254740992.0);
    }
}
