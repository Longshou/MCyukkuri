package io.github.longshou.mcyukkuri.social;

/**
 * One hunger round's budget of silent begs (a look at a window, a drool at a hand that cannot be asked): the begging that is
 * not TN-01's spoken ask, which keeps its own once-per-round record. A new beg needs the cooldown over and the condition
 * changed (hungrier by {@link #WORSE}, or another pane); the cooldown doubles each time and the round ends after
 * {@link #MAX}; a meal back to {@link #FED} starts a new round. Not saved: a reload starts a fresh round.
 */
public final class BegLatch {
    static final int FIRST_COOLDOWN = 400, MAX = 3;
    static final float WORSE = 10;
    static final float FED = 55;

    private float nutrition = Float.NaN;
    private int count;
    private long nextAt;
    private long key;

    /** True when a beg at this pane (0 outdoors) may start now. */
    public boolean allows(long now, float nutrition, long key) {
        if (count == 0) return true;
        if (count >= MAX || now < nextAt) return false;
        return nutrition <= this.nutrition - WORSE || key != this.key;
    }

    /** A beg that has ended (given up, or answered by contact): records the condition and starts the doubled cooldown. */
    public void ended(long now, float nutrition, long key) {
        this.nutrition = nutrition;
        this.key = key;
        nextAt = now + ((long) FIRST_COOLDOWN << count);
        count++;
    }

    /** Called with the current nutrition: eaten back up to {@link #FED}, the round is over. */
    public void fed(float nutrition) {
        if (nutrition >= FED) { count = 0; nextAt = 0; }
    }

    int count() { return count; }
}
