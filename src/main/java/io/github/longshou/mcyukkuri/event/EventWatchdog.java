package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/** Supervision time is overworld game time, independent of active progress and origin-level cooldowns. */
public final class EventWatchdog {
    public static final long CONTINUOUS_LIMIT = 24_000, CUMULATIVE_LIMIT = 72_000;
    public enum Availability { AVAILABLE, PARTICIPANT_UNAVAILABLE, BOUND_TARGET_UNAVAILABLE }

    public static final class State {
        private long lastSupervised = -1, blockedSince = -1, blockedTicks, lastOriginTime;
        private Availability reason = Availability.PARTICIPANT_UNAVAILABLE;

        /** Check the previous blocked interval before accepting a return. No elapsed time is inferred on first observation. */
        public boolean observe(long now, Availability availability, ServerLevel origin) {
            if (origin != null) lastOriginTime = origin.getGameTime();
            if (lastSupervised < 0) {
                lastSupervised = now;
                blockedSince = availability == Availability.AVAILABLE ? -1 : now;
                if (availability != Availability.AVAILABLE) reason = availability;
                return false;
            }
            if (now < lastSupervised) {
                // Re-anchor the continuous interval, never subtract or erase accumulated blocked time.
                if (blockedSince >= 0) blockedSince = now;
            } else if (blockedSince >= 0) {
                blockedTicks = DeadlineMap.after(blockedTicks, DeadlineMap.elapsed(now, lastSupervised));
            }
            lastSupervised = now;
            if (blockedTicks >= CUMULATIVE_LIMIT || blockedSince >= 0 && DeadlineMap.elapsed(now, blockedSince) >= CONTINUOUS_LIMIT) return true;
            if (availability == Availability.AVAILABLE) blockedSince = -1;
            else {
                if (blockedSince < 0) blockedSince = now;
                reason = availability;
            }
            return false;
        }

        public long originTime() { return lastOriginTime; }
        public String timeoutReason() {
            return reason == Availability.BOUND_TARGET_UNAVAILABLE ? "bound_target_unavailable_timeout" : "participant_unavailable_timeout";
        }
        public void save(CompoundTag tag) {
            tag.putLong("LastSupervised",lastSupervised); tag.putLong("BlockedSince",blockedSince);
            tag.putLong("BlockedTicks",blockedTicks); tag.putLong("LastOriginTime",lastOriginTime);
            tag.putString("BlockedReason",reason.name());
        }
        public void load(CompoundTag tag) {
            if (!tag.contains("LastSupervised",Tag.TAG_LONG) || !tag.contains("BlockedSince",Tag.TAG_LONG)
                    || !tag.contains("BlockedTicks",Tag.TAG_LONG) || !tag.contains("LastOriginTime",Tag.TAG_LONG)) return;
            lastSupervised = Math.max(-1,tag.getLong("LastSupervised"));
            blockedSince = Math.max(-1,tag.getLong("BlockedSince"));
            blockedTicks = Math.max(0,tag.getLong("BlockedTicks")); lastOriginTime = tag.getLong("LastOriginTime");
            if (lastSupervised < 0) { blockedSince = -1; blockedTicks = 0; }
            else if (blockedSince > lastSupervised) blockedSince = lastSupervised;
            try { reason = Availability.valueOf(tag.getString("BlockedReason")); }
            catch (IllegalArgumentException ignored) { reason = Availability.PARTICIPANT_UNAVAILABLE; }
        }
    }
    private EventWatchdog() {}
}
