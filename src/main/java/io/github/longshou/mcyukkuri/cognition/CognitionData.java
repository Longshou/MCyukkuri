package io.github.longshou.mcyukkuri.cognition;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
            var n = (CompoundTag)t; if (n.hasUUID("Id")) data.entries.put(n.getUUID("Id"), Entry.load(n));
        }
        return data;
    }
}
