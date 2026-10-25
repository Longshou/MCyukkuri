package io.github.longshou.mcyukkuri.subspecies.crew;

import java.util.UUID;

/**
 * What the deck needs to know about one body, taken from the entity at the moment of the call: its class from its
 * current size, whether it can jump, whether this hull may carry it at all, and any load it brings along (a return stick).
 */
public record Body(UUID id, BodyClass cls, boolean jumps, boolean admitted, double extraLoad) {
    public boolean captain() { return cls.captain(); }
}
