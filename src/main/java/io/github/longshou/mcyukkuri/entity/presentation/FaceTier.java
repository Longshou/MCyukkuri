package io.github.longshou.mcyukkuri.entity.presentation;

/**
 * How long the face of an incidental performance holds: three tiers (a notice, one beat, an individual state), each a range that the
 * individual's own character shifts, floored by the line spoken over it, and followed by a short calming. Pure functions: the entity
 * keeps the state ({@code YukkuriEntity}) and the arbiter reads it (LINGER rank).
 */
public final class FaceTier {
    private FaceTier() {}

    public enum Tier {
        /** A notice, flick, startle or shrug. */
        SHORT(30, 60),
        /** One beat of a performance: an answer, protest, give-up. */
        MEDIUM(70, 130),
        /** The state an individual sits in: a sulk, a verdict, a rejection. Lasting feelings are courses, not this. */
        LONG(160, 320);

        public final int lo, hi;
        Tier(int lo, int hi) { this.lo = lo; this.hi = hi; }

        /** The neutral mapping of a literal tick count: it keeps the tier a plain literal already sat in. */
        public static Tier ofTicks(int ticks) { return ticks <= SHORT.hi ? SHORT : ticks <= MEDIUM.hi ? MEDIUM : LONG; }

        /** Ticks the face stays after its line has ended. */
        public int tail() { return this == LONG ? 30 : 20; }
    }

    /** A newcomer replaces the running hold only when its tier is not lower (urgent and bound ones are decided by the caller). */
    public static boolean replaces(Tier running, Tier incoming) { return incoming.compareTo(running) >= 0; }

    /** How far this face's mood takes the range (0-1) from the traits (0-100): sad by attachment and weak self-control, anger by self-control, fear by boldness, joy by friendliness. */
    public static float lean(FaceMood mood, int attachment, int selfControl, int boldness, int friendliness) {
        return switch (mood) {
            case SAD -> .6f * attachment / 100f + .4f * (1 - selfControl / 100f);
            case ANGRY -> 1 - selfControl / 100f;
            case FEAR -> 1 - boldness / 100f;
            case JOY -> friendliness / 100f;
            case BITTERSWEET, NEUTRAL -> .5f;
        };
    }

    /** The hold in ticks: the tier's range at {@code lean}, +-10% by the individual's own hash (never the random stream), kept inside the range. */
    public static int hold(Tier tier, float lean, int jitterSeed) {
        float jitter = .9f + .2f * ((jitterSeed & 0xFFFF) / 65535f);
        float ticks = (tier.lo + (tier.hi - tier.lo) * Math.clamp(lean, 0f, 1f)) * jitter;
        return Math.clamp(Math.round(ticks), tier.lo, tier.hi);
    }

    /** The face holds at least until its line has ended and the tail after it. */
    public static long holdEnd(long start, int hold, long lineEnd, Tier tier) { return Math.max(start + hold, lineEnd + tier.tail()); }

    /** The calming window after a hold of {@code hold} ticks. */
    public static int fade(int hold) { return Math.clamp(Math.round(.4f * hold), 20, 100); }
}
