package io.github.longshou.mcyukkuri.cognition;

import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.RelationshipCleanup;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** World authority survives custody, entity unload and stale item snapshots. */
public final class CognitionData extends SavedData {
    static final class Entry {
        int innate = 100, learned, motherInnate = -1, fatherInnate = -1, playerLessons;
        String origin = "legacy_default";
        UUID mother, father;
        final Set<UUID> classrooms = new LinkedHashSet<>();
        CompoundTag save() {
            var n = new CompoundTag(); n.putInt("Version", 1); n.putInt("Innate", innate); n.putInt("Learned", learned);
            n.putString("Origin", origin); n.putInt("MotherInnate", motherInnate); n.putInt("FatherInnate", fatherInnate);
            if (mother != null) n.putUUID("Mother", mother); if (father != null) n.putUUID("Father", father);
            n.putInt("PlayerLessons", playerLessons);
            var list = new ListTag(); for (UUID id : classrooms) { var c = new CompoundTag(); c.putUUID("Id", id); list.add(c); }
            n.put("Classrooms", list); return n;
        }
        static Entry load(CompoundTag n) {
            var e = new Entry();
            e.innate = n.contains("Innate", Tag.TAG_INT) ? Math.clamp(n.getInt("Innate"), 0, 1000) : 100;
            e.learned = Math.clamp(n.getInt("Learned"), 0, 1000 - e.innate);
            e.origin = n.getString("Origin"); if (e.origin.isEmpty()) e.origin = "legacy_default";
            e.mother = n.hasUUID("Mother") ? n.getUUID("Mother") : null;
            e.father = n.hasUUID("Father") ? n.getUUID("Father") : null;
            e.motherInnate = e.mother == null || !n.contains("MotherInnate") ? -1 : Math.clamp(n.getInt("MotherInnate"), -1, 1000);
            e.fatherInnate = e.father == null || !n.contains("FatherInnate") ? -1 : Math.clamp(n.getInt("FatherInnate"), -1, 1000);
            e.playerLessons = Math.clamp(n.getInt("PlayerLessons"), 0, 3);
            for (Tag t : n.getList("Classrooms", Tag.TAG_COMPOUND)) {
                var c = (CompoundTag)t; if (c.hasUUID("Id") && e.classrooms.size() < 1000) e.classrooms.add(c.getUUID("Id"));
            }
            return e;
        }
    }
    final Map<UUID, Entry> entries = new HashMap<>();
    private final ArrayDeque<UUID> cleanupRoots = new ArrayDeque<>(), cleanupCandidates = new ArrayDeque<>();
    private Consumer<UUID> retain = cleanupRoots::add;
    private final Set<UUID> cleanupQueued = new HashSet<>();
    private UUID cleanupCandidate;
    private Entry cleanupEntry;
    private RelationshipCleanup.Presence cleanupPresence;
    public void bindCleanup(Consumer<UUID> retention) { retain=retention; }
    public boolean cleanupRootStep() {
        UUID id=cleanupRoots.poll();if(id==null)return false;retain.accept(id);return true;
    }
    private void track(UUID id,Entry entry) {
        if(cleanupQueued.add(id))cleanupCandidates.add(id);
        if(entry.mother!=null)retain.accept(entry.mother);
        if(entry.father!=null)retain.accept(entry.father);
    }
    /** The existing import write, with explicit maintenance bookkeeping before its normal mirror update. */
    void imported(UUID id,Entry entry) { entries.put(id,entry);track(id,entry);setDirty(); }
    /** born() can add parents to an existing Entry; never let its new dependencies wait for a later scan. */
    void changed(Entry entry) {
        if(entry.mother!=null)retain.accept(entry.mother);
        if(entry.father!=null)retain.accept(entry.father);
        setDirty();
    }
    public void cleanupStep(FamilyData family,List<ServerLevel> levels) {
        if(cleanupCandidate==null) {
            UUID id=cleanupCandidates.poll();if(id==null)return;
            Entry entry=entries.get(id);if(entry==null) { cleanupQueued.remove(id);return; }
            cleanupCandidates.add(id);
            if(!family.deadForCleanup(id)||family.retainedForCleanup(id))return;
            cleanupCandidate=id;cleanupEntry=entry;cleanupPresence=new RelationshipCleanup.Presence(id);return;
        }
        if(!cleanupPresence.complete(levels)) { cleanupPresence.step(levels);return; }
        if(!cleanupPresence.alive()&&family.deadForCleanup(cleanupCandidate)&&!family.retainedForCleanup(cleanupCandidate)
                &&entries.get(cleanupCandidate)==cleanupEntry) {
            entries.remove(cleanupCandidate);setDirty();
        }
        cleanupCandidate=null;cleanupEntry=null;cleanupPresence=null;
    }
    /**
     * Playground field clear only (command/PlaygroundTools): drops the entries of individuals the clear removed, so a scene that
     * reuses their fixed UUIDs starts from the entity's own NBT again instead of the previous run's learning.
     */
    public void forget(Set<UUID> ids) {
        if(entries.keySet().removeAll(ids))setDirty();
    }
    public static CognitionData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(CognitionData::new, CognitionData::load), "mcyukkuri_cognition");
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 1); var list = new ListTag();
        entries.forEach((id, e) -> { var n = e.save(); n.putUUID("Id", id); list.add(n); });
        tag.put("Individuals", list); return tag;
    }
    public static CognitionData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new CognitionData();
        for (Tag t : tag.getList("Individuals", Tag.TAG_COMPOUND)) {
            var n = (CompoundTag)t; if (n.hasUUID("Id")) {
                UUID id=n.getUUID("Id");Entry entry=Entry.load(n);data.entries.put(id,entry);data.track(id,entry);
            }
        }
        return data;
    }
}
