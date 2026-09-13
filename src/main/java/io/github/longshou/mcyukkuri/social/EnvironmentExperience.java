package io.github.longshou.mcyukkuri.social;

import java.util.ArrayDeque;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Experienced treatment, independent of food level, temperament and player relationships. */
public final class EnvironmentExperience {
    private float friendliness = 50;
    private long clock, nextCare, nextHostility, hostileUntil;
    private final ArrayDeque<CompoundTag> events = new ArrayDeque<>();
    public int friendliness() { return Math.round(friendliness); }
    public long clock() { return clock; }
    public void tick() {
        clock++;
        if (clock % 1200 == 0) friendliness += Math.signum(50 - friendliness) * Math.min(1, Math.abs(50 - friendliness));
    }
    public boolean record(UUID source, String kind, int contribution) {
        if (source == null || contribution == 0) return false;
        if (contribution > 0) {
            if (clock < nextCare || clock < hostileUntil) return false;
            nextCare = clock + 1200;
            contribution = Math.min(4, contribution);
        } else {
            if (clock < nextHostility) return false;
            nextHostility = clock + 40;
            hostileUntil = clock + 6000;
            contribution = Math.max(-12, contribution);
        }
        friendliness = Math.clamp(friendliness + contribution, 0, 100);
        var event = new CompoundTag(); event.putUUID("Source", source); event.putString("Kind", kind);
        event.putLong("Clock", clock); event.putInt("Contribution", contribution);
        events.addLast(event); while (events.size() > 16) events.removeFirst();
        return true;
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putFloat("Friendliness", friendliness); tag.putLong("Clock", clock);
        tag.putLong("NextCare", nextCare); tag.putLong("NextHostility", nextHostility); tag.putLong("HostileUntil", hostileUntil);
        var list = new ListTag(); for (var event : events) list.add(event.copy()); tag.put("Events", list); return tag;
    }
    public void load(CompoundTag tag) {
        friendliness = tag.contains("Friendliness") && Float.isFinite(tag.getFloat("Friendliness")) ? Math.clamp(tag.getFloat("Friendliness"), 0, 100) : 50;
        clock = Math.max(0, tag.getLong("Clock")); nextCare = Math.clamp(tag.getLong("NextCare"), 0, clock + 1200);
        nextHostility = Math.clamp(tag.getLong("NextHostility"), 0, clock + 40); hostileUntil = Math.clamp(tag.getLong("HostileUntil"), 0, clock + 6000);
        events.clear(); for (Tag raw : tag.getList("Events", Tag.TAG_COMPOUND)) {
            var event = (CompoundTag) raw; if (event.hasUUID("Source")) events.addLast(event.copy());
            if (events.size() > 16) events.removeFirst();
        }
    }
}
