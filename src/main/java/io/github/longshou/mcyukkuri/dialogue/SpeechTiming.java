package io.github.longshou.mcyukkuri.dialogue;

/** Server-only transient admission state; deliberately never saved in entity NBT. */
public final class SpeechTiming {
    private long end, next;
    private int priority = -1;
    public boolean admit(long now, int duration, int incomingPriority) {
        if (now < end && incomingPriority <= priority) return false;
        if (now >= end && now < next && incomingPriority < 2) return false;
        priority = incomingPriority;
        end = now + duration;
        next = end + (incomingPriority == 0 ? 100 : 30);
        return true;
    }
    public long end() { return end; }
}
