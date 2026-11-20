package io.github.longshou.mcyukkuri.settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Track shelter cores and the lifetime of their terrain sections on the server. */
public final class ShelterIndexEvents {
    public static void loaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        invalidateEnclosures(chunk);
        for (var blockEntity : chunk.getBlockEntities().values())
            if (blockEntity instanceof ShelterBlockEntity core) LoadedShelters.register(level, core);
    }
    public static void unloaded(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        invalidateEnclosures(chunk);
        // Match the unloaded instances, rather than deleting a newer chunk's bucket at the same coordinates.
        for (var blockEntity : chunk.getBlockEntities().values())
            if (blockEntity instanceof ShelterBlockEntity core) LoadedShelters.unregister(level, core);
    }
    public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LoadedShelters.clear(level);
    }
    private static void invalidateEnclosures(LevelChunk chunk) {
        for (var section : chunk.getSections()) ((EnclosureTerrain) section).mcyukkuri$invalidateEnclosure();
    }
    public static void stopped(ServerStoppedEvent event) { LoadedShelters.clear(event.getServer()); }
    public static void tagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) NaturalShelter.tagsReloaded();
    }
    private ShelterIndexEvents() {}
}
