package io.github.longshou.mcyukkuri.intent;

import java.util.List;

/** The result a behaviour wants to be true. Read-only in every method: satisfaction is a physical fact, the deadline is absolute and finite, and the method order may come from data. */
public interface Intent {
    /** Lower-case with underscores, e.g. {@code fetch}, {@code get_onto}. */
    String id();

    /** Pure: reads the facts (Attach, Occupancy, position) and never moves anything. */
    boolean satisfied();

    /** Absolute game tick; {@link Long#MAX_VALUE} is refused by {@link IntentRunner}, so no intent waits forever. */
    long deadline();

    /** Ordered; the first applicable, not-excluded method is tried. */
    List<Method> methods();

    default Commitment commitment() { return Commitment.NONE; }

    /** Called once by the {@link IntentRunner} when this intent ends, whatever the result: the one place a domain rule can leave the world consistent again, e.g. a held carry. */
    default void ended(IntentRunner.Result result) {}
}
