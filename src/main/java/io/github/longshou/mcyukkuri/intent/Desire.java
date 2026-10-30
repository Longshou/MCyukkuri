package io.github.longshou.mcyukkuri.intent;

import java.util.Objects;
import java.util.function.Supplier;

/** One want of one body in one domain: the intent it would run, its strength, its expected cost, whether it is a danger desire, the facts it was read from and how to describe them. */
public record Desire(String intentId, double desire, double costEstimate, boolean danger, Object world, Supplier<String> why,
                     Supplier<Intent> intent) {
    public Desire {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(intent, "intent");
        desire = DriveMath.desire(desire);
        costEstimate = Double.isFinite(costEstimate) ? Math.max(0, costEstimate) : 0;
        why = why == null ? () -> "" : why;
    }

    /** A plain desire: no cost estimate, not a danger. */
    public static Desire of(String intentId, double desire, Object world, Supplier<String> why, Supplier<Intent> intent) {
        return new Desire(intentId, desire, 0, false, world, why, intent);
    }
}
