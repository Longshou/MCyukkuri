package io.github.longshou.mcyukkuri.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Transient core candidates, isolated by level instance and bucketed by chunk. No world queries during registration. */
final class LoadedShelters {
    private static final Map<ServerLevel, Map<Long, Map<BlockPos, ShelterBlockEntity>>> LEVELS = new IdentityHashMap<>();
    /** The order of BlockPos.betweenClosed: x changes fastest, then y, then z. */
    private static final Comparator<ShelterBlockEntity> POSITION_ORDER = Comparator
            .comparingInt((ShelterBlockEntity core) -> core.getBlockPos().getZ())
            .thenComparingInt(core -> core.getBlockPos().getY())
            .thenComparingInt(core -> core.getBlockPos().getX());

    static void register(ServerLevel level, ShelterBlockEntity core) {
        // Chunk-load candidates may not have their level assigned yet. Query verifies their actual identity later.
        if (core.isRemoved() || core.getLevel() != null && core.getLevel() != level) return;
        var pos = core.getBlockPos().immutable();
        LEVELS.computeIfAbsent(level, key -> new HashMap<>())
                .computeIfAbsent(chunk(pos), key -> new HashMap<>()).put(pos, core);
    }

    static void unregister(ServerLevel level, ShelterBlockEntity core) {
        var chunks = LEVELS.get(level);
        if (chunks == null) return;
        var key = chunk(core.getBlockPos());
        var cores = chunks.get(key);
        if (cores == null) return;
        // A late removal callback from the old core must not remove its replacement.
        cores.remove(core.getBlockPos(), core);
        if (cores.isEmpty()) chunks.remove(key);
        if (chunks.isEmpty()) LEVELS.remove(level);
    }

    static List<ShelterBlockEntity> within(ServerLevel level, BlockPos first, BlockPos second) {
        var chunks = LEVELS.get(level);
        if (chunks == null) return List.of();
        int minX = Math.min(first.getX(), second.getX()), maxX = Math.max(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY()), maxY = Math.max(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ()), maxZ = Math.max(first.getZ(), second.getZ());
        var result = new ArrayList<ShelterBlockEntity>();
        for (int z = minZ >> 4; z <= maxZ >> 4; z++) for (int x = minX >> 4; x <= maxX >> 4; x++) {
            var cores = chunks.get(ChunkPos.asLong(x, z));
            if (cores == null) continue;
            var loaded = level.getChunkSource().getChunkNow(x, z);
            if (loaded == null) continue;
            for (var entry : cores.entrySet()) {
                var pos = entry.getKey(); var core = entry.getValue();
                if (pos.getX() < minX || pos.getX() > maxX || pos.getY() < minY || pos.getY() > maxY || pos.getZ() < minZ || pos.getZ() > maxZ) continue;
                // Read the existing map only: getBlockEntity could instantiate a pending block entity.
                if (core.getLevel() == level && !core.isRemoved() && loaded.getBlockEntities().get(pos) == core) result.add(core);
            }
        }
        result.sort(POSITION_ORDER);
        return List.copyOf(result);
    }

    static void clear(ServerLevel level) { LEVELS.remove(level); }
    static void clear(MinecraftServer server) { LEVELS.keySet().removeIf(level -> level.getServer() == server); }
    private static long chunk(BlockPos pos) { return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4); }
    private LoadedShelters() {}
}
