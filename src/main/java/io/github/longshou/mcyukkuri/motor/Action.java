package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/**
 * One body execution (L2). The only writer of a {@link YukkuriEntity}'s position, velocity, rotation and
 * riding is {@link Motor}, and the only caller of an action is {@link BodyDriver}.
 */
public interface Action {
    /** Lower-case with underscores; used by logs, the sync slot and Behaviors. */
    String id();

    /** Pure: may this body do it now? No side effects, no movement. */
    Feasibility check(YukkuriEntity body);

    void start(YukkuriEntity body);

    /** Called from {@link BodyDriver#tickTravel}; every tick until it returns an {@link Outcome}. */
    Progress tick(YukkuriEntity body);

    boolean interruptible();

    /** Keep the physical state and {@link Attach#settle}; never roll back, re-seat or teleport. */
    void abort(YukkuriEntity body, String why);

    MotionPose pose();

    /** Watchdog window in ticks: {@code metric} must drop by {@link #progressEpsilon()} within this many ticks. */
    default int progressWindow() { return 40; }

    default double progressEpsilon() { return 0.05; }
}
