package io.github.longshou.mcyukkuri.life;

import java.util.function.BooleanSupplier;

/** Suppress a failed walk while the same origin still has no complete route through the current doorway. */
final class BedRouteRefusal {
    final long from, bed;
    private long nextSurvey;
    private boolean reachable;

    BedRouteRefusal(long from, long bed, long tick) {
        this.from = from;
        this.bed = bed;
        nextSurvey = tick + 20; // Same cadence as a sleeper's ordinary bed and ground checks.
    }

    boolean permits(long origin, long target, long tick, BooleanSupplier survey) {
        if (origin != from || target != bed) return true;
        if (tick >= nextSurvey) {
            reachable = survey.getAsBoolean();
            nextSurvey = tick + 20;
        }
        return reachable;
    }
}
