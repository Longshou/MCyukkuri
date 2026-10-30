package io.github.longshou.mcyukkuri.motor;

import java.util.Objects;

/** Still working; {@code metric} gets smaller as the action approaches its end, and the watchdog reads it. {@code replanned}: the
 *  action changed its way this tick (a route planned round what held it up), so the watchdog measures progress from here. */
public record Running(double metric, TravelMode travel, boolean replanned) implements Progress {
    public Running {
        Objects.requireNonNull(travel, "travel");
    }

    public Running(double metric, TravelMode travel) { this(metric, travel, false); }
}
