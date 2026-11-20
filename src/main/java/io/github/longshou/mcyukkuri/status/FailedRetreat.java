package io.github.longshou.mcyukkuri.status;

import java.util.Objects;
import java.util.UUID;

/** One unsuccessful flight search. Positions are immutable values in the level where it failed. */
final class FailedRetreat<P> {
    // After ten seconds a cornered animal tries again, even if it could not sense what changed.
    static final int RETRY_TICKS = 200;
    // A panic with no known source (fire, thorns) has nothing to wait for: it searches again after a second.
    static final int SOURCELESS_RETRY_TICKS = 20;
    private long retryAt;
    private UUID source, keeper;
    private P feet, danger, destination, refuge;

    void remember(UUID source, P feet, P danger, P destination, long now, UUID keeper, P refuge) {
        remember(source, feet, danger, destination, now, keeper, refuge, RETRY_TICKS);
    }

    void remember(UUID source, P feet, P danger, P destination, long now, UUID keeper, P refuge, int retryTicks) {
        this.source = source;
        this.feet = feet;
        this.danger = danger;
        this.destination = destination;
        this.keeper = keeper;
        this.refuge = refuge;
        retryAt = now + retryTicks;
    }

    boolean stillApplies(long now, UUID keeper, P refuge) {
        return feet != null && now < retryAt && Objects.equals(this.keeper, keeper) && Objects.equals(this.refuge, refuge);
    }

    boolean cornered(UUID source, P feet, P danger) {
        return feet.equals(this.feet) && Objects.equals(this.source, source)
                && (source == null || Objects.equals(this.danger, danger));
    }

    boolean holds(UUID source, P feet, P danger, P destination) {
        return cornered(source, feet, danger) && Objects.equals(this.danger, danger)
                && Objects.equals(this.destination, destination);
    }

    P feet() { return feet; }

    void clear() { feet = null; }
}
