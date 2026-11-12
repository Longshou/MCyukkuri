package io.github.longshou.mcyukkuri.motor.geom;

/**
 * A local copy of a remote tick clock that moves on every local tick and eases toward each re-sync of the remote time instead of
 * jumping to it: it never runs backward, and each local tick moves it one tick, give or take {@link #EASE}. No Minecraft types.
 */
public final class EasedClock {
    /**
     * How much faster or slower than one tick per local tick the clock may run while it closes a re-sync's difference (project
     * design). This closes the 2 to 5 ticks of drift estimated in a playtest session within ten ticks, around the length of
     * a brim hop's landing recovery.
     */
    public static final double EASE = .5;
    private double previous, current;

    public EasedClock(long target) {
        previous = target - 1;
        current = target;
    }

    /** One local tick: {@code target} is the remote time as now known, {@code ticked} whether the local copy advanced this tick. */
    public void step(long target, boolean ticked) {
        // A paused frame keeps its fractional tick, so keep both endpoints of that tick as well.
        if (!ticked) return;
        double drift = target - (current + 1);
        previous = current;
        current += 1 + Math.clamp(drift, -EASE, EASE);
    }

    /** The time {@code partial} of the way through the current local tick, continuous across ticks ({@code target + partial} when in step). */
    public double at(float partial) { return previous + 1 + partial * (current - previous); }
}
