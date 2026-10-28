package io.github.longshou.mcyukkuri.motor;

/**
 * The behaviour that requested the current action (a Goal wrapper switches {@link #active()} in start/stop).
 * The spec lists this type under {@code intent/}; it lives in {@code motor/} because {@link BodyDriver} needs it
 * and {@code motor/} must not depend on {@code intent/}.
 */
public interface MotorOwner {
    String ownerId();

    boolean active();
}
