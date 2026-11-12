package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.motor.geom.EasedClock;
import net.minecraft.world.level.Level;

/**
 * The game time a timeline the server committed in absolute ticks is sampled at (a brim young's ride, walk and hops onto its
 * spot). The server reads its own time. The client's copy of it moves once per client tick and is set to the server's every
 * 20 server ticks, so it jumps by however far the two loops ran apart (an overloaded or catching-up server): a hop sampled by
 * it replays or skips part of its arc. The client samples such a timeline with the eased copy instead ({@link EasedClock}).
 */
public final class TimelineClock {
    private static Level level;
    private static EasedClock clock;

    public static double now(Level level, float partial) {
        return level.isClientSide && level == TimelineClock.level ? clock.at(partial) : level.getGameTime() + partial;
    }

    /** At the end of each client tick: the client's level ({@code null} without one) and whether its time advanced this tick. */
    public static void tick(Level level, boolean ticked) {
        if (level != TimelineClock.level) {
            TimelineClock.level = level;
            clock = new EasedClock();
        }
        if (level != null) clock.step(level.getGameTime(), ticked);
    }

    private TimelineClock() {}
}
