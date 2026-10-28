package io.github.longshou.mcyukkuri.motor;

/** Whether this tick's movement is already done. */
public enum TravelMode {
    /** The action moved the body itself; vanilla {@code travel} must not integrate again. */
    CONSUMED,
    /** Hand the tick to vanilla {@code travel} (movement, gravity, drag, collision). */
    PASS
}
