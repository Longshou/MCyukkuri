package io.github.longshou.mcyukkuri.perception;

/** What a body believes about a fixture it knows of: pure state rules; {@link FixtureBelief} holds them. */
public final class Belief {
    public enum State { FULL, EMPTY, UNKNOWN }

    /** The last look: when, and whether the fixture held what the body wants. */
    public record Entry(long tick, boolean stocked) {}

    /** Ticks an "empty" look stays believed: long when content, short when the need is pressing. */
    private static final int DECAY_CALM = 2400, DECAY_URGENT = 300;
    /** Most fixtures one body keeps a belief about. */
    public static final int CAPACITY = 16;

    private Belief() {}

    /** Urgency 0..1 of thirst (dryness from the find-water threshold up to parched) and of hunger (nutrition from the ask line down to starving). */
    public static double waterUrgency(double dryness) { return Sight.ramp(dryness, 40, 90); }
    public static double foodUrgency(double nutrition) { return Sight.ramp(45 - nutrition, 0, 35); }

    public static long decay(double urgency) {
        return Math.round(DECAY_CALM - (DECAY_CALM - DECAY_URGENT) * Math.clamp(urgency, 0, 1));
    }

    /** A stocked look stays believed until the body looks again; an empty look fades to unknown so the body goes to check. */
    public static State state(Entry entry, long now, long decay) {
        if (entry == null) return State.UNKNOWN;
        if (entry.stocked()) return State.FULL;
        return now - entry.tick() >= decay ? State.UNKNOWN : State.EMPTY;
    }
}
