package io.github.longshou.mcyukkuri.dialogue;

/** Server-only transient admission state; deliberately never saved in entity NBT. */
public final class SpeechTiming {
    private long end, next, quiet;
    private int priority = -1;

    /**
     * No line starts while one runs, and none starts inside the gap after it, except an urgent one (wounds, fright, loss: its own
     * natural priority is 3 or more, never a floor a caller gave it) of a higher priority than the line it breaks in on. After an
     * ordinary line (priority below 2) a further quiet keeps ordinary chatter apart.
     */
    private boolean available(long now, int incomingPriority, boolean urgent) {
        if (now < next) return urgent && incomingPriority > priority;
        return !(now < quiet && incomingPriority < 2);
    }

    /** Whether a line may start now (the body's own turn among its neighbours is checked beside this, in {@code YukkuriEntity}).
     *  A refused beat line is dropped, not queued (it would come stale). */
    public boolean canSpeak(long now, int incomingPriority, boolean urgent) {
        return available(now, incomingPriority, urgent);
    }

    /** Whether the speaker is between lines: nothing running and the gap after the last one over. */
    public boolean idle(long now) { return now >= next; }

    /** Keeps the door shut at least until {@code until}: a nearby line still fading staggers the retry. */
    public void defer(long until) { quiet = Math.max(quiet, until); }

    public boolean admit(long now, int duration, int incomingPriority, boolean urgent) {
        if (!available(now, incomingPriority, urgent)) return false;
        begin(now, duration, incomingPriority);
        return true;
    }

    public void begin(long now, int duration, int incomingPriority) {
        priority = incomingPriority;
        end = now + duration;
        next = end + 30 + duration / 2;
        quiet = end + gap(duration, incomingPriority);
    }

    /** The quiet after a line before the next ordinary line: longer after chatter, the line's own length after a performance line. */
    static int gap(int duration, int priority) {
        return priority == 0 ? 200 : priority == 1 ? 120 : 30 + duration / 2;
    }
    public long end() { return end; }
    public void interrupt() { end = next = quiet = 0; priority = -1; }
}
