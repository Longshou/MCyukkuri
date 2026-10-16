package io.github.longshou.mcyukkuri.pet;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-player time of the last abandonment (game time), kept so the examiner can judge whether the owner can be expected to manage a pet (docs/PET_SHOP.md 3, simple exam). */
public final class AbandonLog extends SavedData {
    /** Three game days. */
    public static final long WINDOW = 72000;
    private final Map<UUID, Long> last = new HashMap<>();

    public static AbandonLog get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(AbandonLog::new, AbandonLog::load), "mcyukkuri_abandon_log");
    }
    public void record(UUID player, long gameTime) { if (player != null) { last.put(player, gameTime); setDirty(); } }
    public void clear(UUID player) { if (last.remove(player) != null) setDirty(); }
    public boolean recent(UUID player, long gameTime) { var t = last.get(player); return t != null && gameTime >= t && gameTime - t < WINDOW; }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var e : last.entrySet()) { var c = new CompoundTag(); c.putUUID("Player", e.getKey()); c.putLong("Time", e.getValue()); list.add(c); }
        tag.put("Abandoned", list); return tag;
    }
    public static AbandonLog load(CompoundTag tag, HolderLookup.Provider registries) {
        var log = new AbandonLog();
        for (var e : tag.getList("Abandoned", 10)) { var c = (CompoundTag) e; if (c.hasUUID("Player")) log.last.put(c.getUUID("Player"), c.getLong("Time")); }
        return log;
    }
}
