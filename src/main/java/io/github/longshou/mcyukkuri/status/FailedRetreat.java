package io.github.longshou.mcyukkuri.status;

import java.util.Objects;
import java.util.UUID;

/** One unsuccessful flight search. Positions are immutable values in the level where it failed. */
final class FailedRetreat<P> {
    private UUID source;
    private P feet, danger, destination;

    void remember(UUID source, P feet, P danger, P destination) {
        this.source = source;
        this.feet = feet;
        this.danger = danger;
        this.destination = destination;
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
