package io.github.longshou.mcyukkuri.intent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One want of one body in one domain (spec 11.4): the intent it would run, how strongly it wants it (0-100, clamped), what
 * it expects that to cost, whether it is a danger desire (fear, drowning, hurt) that may take a {@link Commitment#HARD}
 * intent, and the facts it was read from ({@code why}, for the {@code [intent]} line and the readout). The intent is built
 * only when {@link Drive} picks this desire; the data pack weight of the intent id multiplies the desire in the score.
 */
public record Desire(String intentId, double desire, double costEstimate, boolean danger, String why, Supplier<Intent> intent) {
    public Desire {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(intent, "intent");
        desire = DriveMath.desire(desire);
        costEstimate = Double.isFinite(costEstimate) ? Math.max(0, costEstimate) : 0;
        why = why == null ? "" : why;
    }

    /** A plain desire: no cost estimate, not a danger. */
    public static Desire of(String intentId, double desire, String why, Supplier<Intent> intent) {
        return new Desire(intentId, desire, 0, false, why, intent);
    }
}
