package io.github.longshou.mcyukkuri.motor;

import java.util.Objects;

/** Still working; {@code metric} gets smaller as the action approaches its end, and the watchdog reads it. */
public record Running(double metric, TravelMode travel) implements Progress {
    public Running {
        Objects.requireNonNull(travel, "travel");
    }
}
