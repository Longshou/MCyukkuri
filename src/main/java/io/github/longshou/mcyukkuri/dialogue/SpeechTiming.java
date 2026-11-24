package io.github.longshou.mcyukkuri.dialogue;

/** Server-only transient admission state; deliberately never saved in entity NBT. */
public final class SpeechTiming {
    private long end, next;
    private int priority = -1;

    private boolean available(long now, int incomingPriority, boolean timedCue) {
        // A synchronized performance beat may take over another priority-two line.
        if (now < end && (incomingPriority < priority
                || incomingPriority == priority && !(timedCue && incomingPriority == 2))) return false;
        if (now >= end && now < next && (incomingPriority < 2 || incomingPriority <= priority)) return false;
        return true;
    }

    /** Whether a line may start now (the body's own turn among its neighbours is checked beside this, in {@code YukkuriEntity}).
     *  Inside the gap after a performance line only a line of a higher priority speaks; a refused beat line is dropped, not queued
     *  (it would come stale). */
    public boolean canSpeak(long now, int incomingPriority, boolean timedCue) {
        return available(now, incomingPriority, timedCue);
    }

    /** Keeps the door shut at least until {@code until}: a nearby line still fading staggers the retry. */
    public void defer(long until) { next = Math.max(next, until); }

    public boolean admit(long now, int duration, int incomingPriority) {
        if (!available(now, incomingPriority, false)) return false;
        begin(now, duration, incomingPriority);
        return true;
    }

    public void begin(long now, int duration, int incomingPriority) {
        priority = incomingPriority;
        end = now + duration;
        next = end + gap(duration, incomingPriority);
    }

    /** The quiet after a line before the next ordinary or beat line; a performance line (priority two) leaves a gap that grows with its length. */
    static int gap(int duration, int priority) {
        return priority == 0 ? 200 : priority == 1 ? 120 : 30 + duration / 2;
    }
    public long end() { return end; }
    public void interrupt() { end = next = 0; priority = -1; }
}
