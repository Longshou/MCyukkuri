package io.github.longshou.mcyukkuri.motor;

/** The behaviour that requested the current action (a Goal wrapper switches {@link #active()} in start/stop); it lives in {@code motor/} because {@link BodyDriver} needs it. */
public interface MotorOwner {
    String ownerId();

    boolean active();

    /** Observe the real result, even if the goal stopped during a short uninterruptible action. Never starts another action. */
    default void finished(Action action, Outcome outcome) {}
}
