package io.github.longshou.mcyukkuri.subspecies.boat;

/**
 * One thing a rider of an afloat hull can be doing, registered with {@link AfloatAgenda}. The agenda runs them in priority order
 * (lower number first), one at a time; when a more urgent one takes over, the running one gets {@link #stop} with
 * {@code preempted} and is never resumed on its own: it competes again like any other. Every activity states how long it may last
 * and, if it can tell, how far along it is, so a stuck one ends with a reason instead of holding the captain forever.
 */
public interface AfloatActivity {
    /** Stable name for logs. */
    String id();

    /** Order in the agenda, lower first (the D-ecology table: 1 incident, 2 threat, 3 labor, 4 physiology, 5 care ... 11 idle). */
    double priority();

    /** The longest this activity may stay the running one before it is ended with {@code timeout}. */
    int maxTicks();

    /** How long the activity stays out of the running after hitting its time limit; a base activity (idle, passenger) answers 0. */
    default int cooldownAfterTimeout() { return AfloatAgenda.TIMEOUT_COOLDOWN; }

    /** Cheap and side-effect free: may this activity take the turn at all right now? */
    boolean wants(AfloatAgenda.Context c);

    /**
     * The activity is about to be tried: reset its own fields only. It may be called again on every tick it is tried and declined,
     * so it must touch nothing outside the activity.
     */
    default void start(AfloatAgenda.Context c) {}

    /** The activity took its first turn and is now the running one: the place to yield other controllers' work. */
    default void adopted(AfloatAgenda.Context c) {}

    /** Run one tick. True when the activity took the turn and is under way, false when it declined or is finished. */
    boolean tick(AfloatAgenda.Context c);

    /** The running activity ended: {@code reason} is one of done, preempted, timeout, stalled, lapsed. It must leave nothing running. */
    default void stop(AfloatAgenda.Context c, String reason) {}

    /** A number that changes whenever the activity makes progress; {@link Long#MIN_VALUE} when it has no such measure. */
    default long progress(AfloatAgenda.Context c) { return Long.MIN_VALUE; }
}
