package io.github.longshou.mcyukkuri.settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Register once on the game bus; chunk callbacks inspect only the event chunk's existing block entities. */
public final class ShelterIndexEvents {
    public static void loaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        for (var blockEntity : chunk.getBlockEntities().values())
            if (blockEntity instanceof ShelterBlockEntity core) LoadedShelters.register(level, core);
    }
    public static void unloaded(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        // Match the unloaded instances, rather than deleting a newer chunk's bucket at the same coordinates.
        for (var blockEntity : chunk.getBlockEntities().values())
            if (blockEntity instanceof ShelterBlockEntity core) LoadedShelters.unregister(level, core);
    }
    public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LoadedShelters.clear(level);
    }
    public static void stopped(ServerStoppedEvent event) { LoadedShelters.clear(event.getServer()); }
    private ShelterIndexEvents() {}
}
