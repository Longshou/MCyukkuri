package io.github.longshou.mcyukkuri.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Per-level snapshots of real bell and bed block entities in already loaded chunks. */
final class VillageSurvey {
    record Streets(BlockPos address, BlockPos centre, List<BlockPos> beds) {
        boolean contains(BlockPos pos) {
            return StrayDensity.stillResident(true, pos.getX() - address.getX(), pos.getZ() - address.getZ());
        }
    }
    private record Landmarks(List<BlockPos> bells, List<BlockPos> beds) {
        BlockPos anchor(BlockPos near) {
            return (bells.isEmpty() ? beds : bells).stream().min(Comparator
                    .<BlockPos>comparingDouble(p -> horizontal(p, near)).thenComparing(BlockPos::compareTo)).orElse(null);
        }
    }

    static final class Cache<K, V> {
        private record Entry<V>(long expires, V value) {}
        private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, .75f, true);

        V get(K key, long now, Supplier<V> survey) {
            var entry = entries.get(key);
            if (entry != null && now < entry.expires) return entry.value;
            var value = survey.get();
            entries.put(key, new Entry<>(now + 6000, value));
            if (entries.size() > 256) entries.pollFirstEntry();
            return value;
        }
    }

    private final Cache<Long, BlockPos> anchors = new Cache<>();
    private final Cache<BlockPos, Streets> streets = new Cache<>();

    Streets at(ServerLevel level, BlockPos pos) {
        var chunk = new ChunkPos(pos);
        var key = anchors.get(chunk.toLong(), level.getGameTime(), () -> {
            var generated = generatedAddress(level, chunk);
            if (generated != null) return generated;
            // A street outside the structure can still belong to its bell.
            var middle = chunk.getMiddleBlockPosition(pos.getY());
            var anchor = landmarks(level, middle).anchor(middle);
            if (anchor == null) return null;
            var village = generatedAddress(level, new ChunkPos(anchor));
            return village == null ? anchor : village;
        });
        if (key == null) return null;
        return streets.get(key, level.getGameTime(), () -> {
            var sites = landmarks(level, key);
            var bell = sites.anchor(key);
            if (bell == null) return new Streets(key, key, List.of());
            if (horizontal(bell, key) != 0) sites = landmarks(level, bell);
            return new Streets(key, bell, sites.beds);
        });
    }

    private static BlockPos generatedAddress(ServerLevel level, ChunkPos chunk) {
        var loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
        if (loaded == null) return null;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ChunkPos nearest = null;
        for (var entry : loaded.getAllReferences().entrySet()) {
            if (!registry.wrapAsHolder(entry.getKey()).is(StructureTags.VILLAGE)) continue;
            for (long start : entry.getValue()) {
                var village = new ChunkPos(start);
                if (nearest == null || chunk.distanceSquared(village) < chunk.distanceSquared(nearest)) nearest = village;
            }
        }
        return nearest == null ? null : nearest.getMiddleBlockPosition(0);
    }

    static double horizontal(BlockPos a, BlockPos b) {
        return a.distToLowCornerSqr(b.getX(), a.getY(), b.getZ());
    }

    private static Landmarks landmarks(ServerLevel level, BlockPos centre) {
        var bells = new ArrayList<BlockPos>();
        var beds = new ArrayList<BlockPos>();
        int r = StrayDensity.RADIUS;
        for (int x = (centre.getX() - r) >> 4; x <= (centre.getX() + r) >> 4; x++)
            for (int z = (centre.getZ() - r) >> 4; z <= (centre.getZ() + r) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var p : chunk.getBlockEntitiesPos()) {
                    if (!StrayDensity.inFootprint(p.getX() - centre.getX(), p.getZ() - centre.getZ())) continue;
                    var state = chunk.getBlockState(p);
                    if (state.is(Blocks.BELL)) bells.add(p);
                    if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) beds.add(p);
                }
            }
        beds.sort(BlockPos::compareTo);
        return new Landmarks(bells, beds);
    }
}
