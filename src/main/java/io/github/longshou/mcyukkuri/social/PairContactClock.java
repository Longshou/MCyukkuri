package io.github.longshou.mcyukkuri.social;

import java.util.function.IntConsumer;

/** Credits one frame only after both roles have received it. */
final class PairContactClock {
    private final int length;
    private int ticks;
    private long publishedAt = Long.MIN_VALUE;

    PairContactClock(int length) { this.length = length; }

    int ticks() { return ticks; }
    boolean complete() { return ticks == length; }
    boolean finished(long now) { return complete() && now > publishedAt; }

    /** The frame cleared in the tick that published it never reached either end-of-tick draw. */
    int releasedTicks(long now) { return now == publishedAt ? ticks - 1 : ticks; }

    void publish(long now, IntConsumer first, IntConsumer second) {
        if (now == publishedAt || complete()) return;
        first.accept(ticks);
        second.accept(ticks);
        publishedAt = now;
        ticks++;
    }
}
