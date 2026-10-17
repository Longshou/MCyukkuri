package io.github.longshou.mcyukkuri.pet.social;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** One individual's memory of other individuals it has noticed a badge on (or lost one): at most 12 entries, decayed lazily when read,
 *  saved with the owner's PetLife. It records why and what last happened; the affinity number itself stays in YukkuriWorldData. */
public final class BadgeLedger {
    public static final int CAPACITY = 12, MAX_SEEN = 9, MAX_IMPRESSION = 3;
    /** Impression moves one step toward 0 per game day; an entry unseen for five days is forgotten. */
    public static final long DECAY_TICKS = 24000L, STALE_TICKS = 5 * 24000L;
    public static final int ADMIRED = 1, ENVIED = 2, FED_BY = 4, HELPED = 8, SNUBBED = 16, MOCKED = 32, ROBBED_BY = 64, ROBBED = 128,
            PLEADED_OWNER = 256, TOLD_ON = 512, WARY = 1024;

    public static final class Entry {
        public final UUID subject;
        private long firstSeen, lastSeen, lastArcTick = -1, stamp;
        private int seenCount, lastGrade, impression, flags;
        private String lastArc = "";
        Entry(UUID subject) { this.subject = subject; }
        public long firstSeen() { return firstSeen; }
        public long lastSeen() { return lastSeen; }
        public int seenCount() { return seenCount; }
        /** Visible grade (0 none .. 3 gold) the last time the subject was noticed. */
        public int lastGrade() { return lastGrade; }
        public int impression() { return impression; }
        public int flags() { return flags; }
        public boolean has(int flag) { return (flags & flag) != 0; }
        public String lastArc() { return lastArc; }
        public long lastArcTick() { return lastArcTick; }
        /** Ticks since an arc last played with this subject, or Long.MAX_VALUE if none ever did. */
        public long sinceArc(long now) { return lastArcTick < 0 ? Long.MAX_VALUE : Math.max(0, now - lastArcTick); }
    }

    /** What a fresh sighting means, computed before the entry is updated. */
    public record Sighting(Entry entry, boolean first, boolean changed, boolean lapsed, int previousGrade) {}

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public int size() { return entries.size(); }
    public void clear() { entries.clear(); }

    /** The entry after lazy decay, or null when absent or forgotten. */
    public Entry get(UUID subject, long now) {
        var e = entries.get(subject);
        if (e == null) return null;
        if (now - e.lastSeen > STALE_TICKS) { entries.remove(subject); return null; }
        decay(e, now);
        return e;
    }

    private static void decay(Entry e, long now) {
        long steps = (now - e.stamp) / DECAY_TICKS;
        if (steps <= 0) return;
        int magnitude = (int)Math.max(0, Math.abs(e.impression) - Math.min(steps, 8));
        e.impression = (int)Math.signum(e.impression) * magnitude;
        e.stamp += steps * DECAY_TICKS;
    }

    /** Record that the subject was seen carrying (visible) grade 0..3. A lapse is a visible grade below the one last noticed. */
    public Sighting note(UUID subject, int grade, long now) {
        var e = get(subject, now);
        boolean first = e == null;
        int previous = first ? -1 : e.lastGrade;
        boolean changed = !first && previous != grade;
        boolean lapsed = !first && grade < previous;
        if (first) { e = insert(subject, now); e.firstSeen = now; e.stamp = now; }
        e.lastSeen = now; e.seenCount = Math.min(MAX_SEEN, e.seenCount + 1); e.lastGrade = grade;
        return new Sighting(e, first, changed, lapsed, previous);
    }

    private Entry insert(UUID subject, long now) {
        if (entries.size() >= CAPACITY) {
            // Forget the least memorable: smallest impression magnitude, then the longest unseen.
            entries.values().stream().min(Comparator.<Entry>comparingInt(x -> Math.abs(x.impression)).thenComparingLong(x -> x.lastSeen))
                    .ifPresent(worst -> entries.remove(worst.subject));
        }
        var e = new Entry(subject); entries.put(subject, e); return e;
    }

    public void impress(UUID subject, int delta, long now) {
        var e = get(subject, now);
        if (e != null) e.impression = Math.clamp(e.impression + delta, -MAX_IMPRESSION, MAX_IMPRESSION);
    }
    public void flag(UUID subject, int flags, long now) {
        var e = get(subject, now);
        if (e != null) e.flags |= flags;
    }
    public void unflag(UUID subject, int flags, long now) {
        var e = get(subject, now);
        if (e != null) e.flags &= ~flags;
    }
    public void arc(UUID subject, String arcId, long now) {
        var e = get(subject, now);
        if (e != null) { e.lastArc = arcId == null ? "" : arcId; e.lastArcTick = now; }
    }
    /** Entry creation for a subject that was never "sighted" (a settled arc always leaves a trace). */
    public Entry ensure(UUID subject, int grade, long now) {
        var e = get(subject, now);
        if (e == null) { e = insert(subject, now); e.firstSeen = e.lastSeen = now; e.stamp = now; e.lastGrade = grade; e.seenCount = 1; }
        return e;
    }
    public List<Entry> view(long now) {
        List<Entry> result = new ArrayList<>();
        for (var id : List.copyOf(entries.keySet())) { var e = get(id, now); if (e != null) result.add(e); }
        return result;
    }

    public CompoundTag save() {
        var tag = new CompoundTag(); var list = new ListTag();
        for (var e : entries.values()) {
            var n = new CompoundTag(); n.putUUID("Subject", e.subject);
            n.putLong("FirstSeen", e.firstSeen); n.putLong("LastSeen", e.lastSeen); n.putLong("Stamp", e.stamp); n.putLong("LastArcTick", e.lastArcTick);
            n.putInt("Seen", e.seenCount); n.putInt("Grade", e.lastGrade); n.putInt("Impression", e.impression); n.putInt("Flags", e.flags);
            n.putString("LastArc", e.lastArc); list.add(n);
        }
        tag.put("Entries", list); return tag;
    }
    public void load(CompoundTag tag) {
        entries.clear();
        for (Tag raw : tag.getList("Entries", Tag.TAG_COMPOUND)) {
            var n = (CompoundTag)raw;
            if (!n.hasUUID("Subject") || entries.size() >= CAPACITY) continue;
            var e = new Entry(n.getUUID("Subject"));
            e.firstSeen = n.getLong("FirstSeen"); e.lastSeen = n.getLong("LastSeen"); e.stamp = n.getLong("Stamp");
            e.lastArcTick = n.contains("LastArcTick") ? n.getLong("LastArcTick") : -1;
            e.seenCount = Math.clamp(n.getInt("Seen"), 0, MAX_SEEN); e.lastGrade = Math.clamp(n.getInt("Grade"), 0, 3);
            e.impression = Math.clamp(n.getInt("Impression"), -MAX_IMPRESSION, MAX_IMPRESSION); e.flags = n.getInt("Flags");
            var arc = n.getString("LastArc"); e.lastArc = arc.length() > 24 ? "" : arc;
            entries.put(e.subject, e);
        }
    }
}
