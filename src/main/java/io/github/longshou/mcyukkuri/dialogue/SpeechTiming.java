package io.github.longshou.mcyukkuri.dialogue;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Server-only transient admission state; deliberately never saved in entity NBT. */
public final class SpeechTiming {
    private static final int NEARBY_RADIUS = 16;
    private static final int NEARBY_QUIET_TICKS = 80;
    private long end, next;
    private int priority = -1;

    private boolean available(long now, int incomingPriority) {
        if (now < end && incomingPriority <= priority) return false;
        if (now >= end && now < next && incomingPriority < 2) return false;
        return true;
    }

    /** Ordinary speech takes turns locally; important reactions bypass the shared quiet gap. */
    public boolean admit(YukkuriEntity speaker, long now, int duration, int incomingPriority) {
        if (!available(now, incomingPriority)) return false;
        if (incomingPriority < 2 && !speaker.level().getEntitiesOfClass(YukkuriEntity.class,
                speaker.getBoundingBox().inflate(NEARBY_RADIUS), other -> other != speaker && other.isAlive()
                        && speaker.distanceToSqr(other) <= NEARBY_RADIUS * NEARBY_RADIUS
                        && other.speechSequence() != 0 && now < other.speechEnd() + NEARBY_QUIET_TICKS).isEmpty()) {
            // Stagger retries without consuming the ecology/behaviour random stream.
            next = Math.max(next, now + 40 + Math.floorMod(speaker.getUUID().hashCode(), 41));
            return false;
        }
        return admit(now, duration, incomingPriority);
    }

    public boolean admit(long now, int duration, int incomingPriority) {
        if (!available(now, incomingPriority)) return false;
        priority = incomingPriority;
        end = now + duration;
        next = end + (incomingPriority == 0 ? 200 : incomingPriority == 1 ? 120 : 30);
        return true;
    }
    public long end() { return end; }
}
