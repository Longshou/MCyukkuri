package io.github.longshou.mcyukkuri.motor.geom;

/**
 * A local copy of a remote tick clock that moves on every local tick and eases toward each re-sync of the remote time instead of
 * jumping to it: it never runs backward, and each local tick moves it one tick, give or take {@link #EASE}. No Minecraft types.
 */
public final class EasedClock {
    /**
     * How much faster or slower than one tick per local tick the clock may run while it closes a re-sync's difference (project
     * design). A re-sync of an overloaded or catching-up server differs by a few ticks (2 to 5 measured in a playtest session); at
     * half a tick per tick that closes within ten ticks, inside the nine-tick recovery after which a brim hop's next leg is committed.
     */
    public static final double EASE = .5;
    /** A difference beyond one re-sync period (ticks) is not two loops running at about the same rate but a new time (a stall, a
     *  set time, a joined world): taken at once. */
    public static final double RESET = 20;
    private double previous, current;
    private boolean started;

    /** One local tick: {@code target} is the remote time as now known, {@code ticked} whether the local copy advanced this tick. */
    public void step(long target, boolean ticked) {
        if (!started) {
            started = true;
            previous = target - 1;
            current = target;
            return;
        }
        double advance = ticked ? 1 : 0, drift = target - (current + advance);
        previous = current;
        current = Math.abs(drift) > RESET ? target : current + Math.max(0, advance + Math.clamp(drift, -EASE, EASE));
    }

    /** The time {@code partial} of the way through the current local tick, continuous across ticks ({@code target + partial} when in step). */
    public double at(float partial) { return previous + 1 + partial * (current - previous); }
}
