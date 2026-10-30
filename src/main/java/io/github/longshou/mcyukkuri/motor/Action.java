package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** One body execution. Only {@link Motor} writes a body, and only {@link BodyDriver} starts an action. */
public interface Action {
    /** Lower-case with underscores; used by logs, the sync slot and Behaviors. */
    String id();

    /** Pure: may this body do it now? No side effects, no movement. */
    Feasibility check(YukkuriEntity body);

    void start(YukkuriEntity body);

    /** Called from {@link BodyDriver#tickTravel}; every tick until it returns an {@link Outcome}. */
    Progress tick(YukkuriEntity body);

    /** Whether another request may end it now; most actions can be let go of at any tick. */
    default boolean interruptible() { return true; }

    /** Keep the physical state ({@link BodyDriver} then settles it); never roll back, re-seat or teleport. */
    default void abort(YukkuriEntity body, String why) {}

    /** The clip it plays; nothing for an action that animates nothing. */
    default MotionPose pose() { return MotionPose.NONE; }

    /** Extra fields for the {@code [action] start} line; empty when the action has none to add. */
    default String startDetail() { return ""; }

    /** Extra fields for the {@code [action] end} line; empty when the action has none to add. */
    default String endDetail(Outcome outcome) { return ""; }

    /** Watchdog window in ticks: {@code metric} must drop by {@link #progressEpsilon()} within this many ticks. */
    default int progressWindow() { return 40; }

    default double progressEpsilon() { return 0.05; }
}
