package io.github.longshou.mcyukkuri.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Short-lived, level-local cache of inspected heat-source positions.
 *
 * <p>A query fills only the part of a small cell which overlaps its original search box. Thus its
 * cold-cache cost stays exactly the old search volume, while nearby later queries reuse inspected
 * blocks and only fill a newly entered edge. Cells lie inside a single chunk in X/Z; an unloaded
 * cell is discarded and never causes a chunk load.</p>
 */
final class ThermalSourceCache {
    record Source(BlockPos pos, double strength) {}

    private record Cell(int x, int y, int z) {}
    private static final class Entry {
        long checkedAt;
        long knownLow, knownHigh;
        final List<Source> sources = new ArrayList<>();
        Entry(long checkedAt) { this.checkedAt = checkedAt; }
        void refresh(long now) {
            checkedAt = now;
            knownLow = knownHigh = 0;
            sources.clear();
        }
        boolean known(int index) {
            return index < 64 ? (knownLow & 1L << index) != 0 : (knownHigh & 1L << (index - 64)) != 0;
        }
        void markKnown(int index) {
            if (index < 64) knownLow |= 1L << index;
            else knownHigh |= 1L << (index - 64);
        }
    }

    private static final int CELL_XZ = 4;
    private static final int CELL_Y = 5;
    private static final int REFRESH_TICKS = 20;
    private static final int MAX_CELLS = 2048;
    private static final Map<ServerLevel, ThermalSourceCache> LEVELS = new WeakHashMap<>();

    private final Map<Cell, Entry> cells = new HashMap<>();

    private static ThermalSourceCache of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, unused -> new ThermalSourceCache());
    }

    /** Returns cached candidates only; callers still apply their exact radius and sight rules. */
    static List<Source> sources(ServerLevel level, BlockPos center, int radius) {
        return of(level).find(level, center, radius);
    }

    private List<Source> find(ServerLevel level, BlockPos center, int radius) {
        int lowX = center.getX() - radius, highX = center.getX() + radius;
        int lowY = center.getY() - 2, highY = center.getY() + 2;
        int lowZ = center.getZ() - radius, highZ = center.getZ() + radius;
        int minX = Math.floorDiv(lowX, CELL_XZ), maxX = Math.floorDiv(highX, CELL_XZ);
        int minY = Math.floorDiv(lowY, CELL_Y), maxY = Math.floorDiv(highY, CELL_Y);
        int minZ = Math.floorDiv(lowZ, CELL_XZ), maxZ = Math.floorDiv(highZ, CELL_XZ);
        long now = level.getGameTime();
        List<Source> found = List.of();
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            var cell = new Cell(x, y, z);
            var entry = entry(level, cell, now);
            if (entry == null) continue;
            int originX = x * CELL_XZ, originY = y * CELL_Y, originZ = z * CELL_XZ;
            int startX = Math.max(lowX, originX), endX = Math.min(highX, originX + CELL_XZ - 1);
            int startY = Math.max(lowY, originY), endY = Math.min(highY, originY + CELL_Y - 1);
            int startZ = Math.max(lowZ, originZ), endZ = Math.min(highZ, originZ + CELL_XZ - 1);
            inspect(level, cell, entry, startX, endX, startY, endY, startZ, endZ);
            for (var source : entry.sources) {
                var pos = source.pos();
                if (pos.getX() >= lowX && pos.getX() <= highX && pos.getY() >= lowY && pos.getY() <= highY
                        && pos.getZ() >= lowZ && pos.getZ() <= highZ) {
                    if (found.isEmpty()) found = new ArrayList<>();
                    found.add(source);
                }
            }
        }
        return found;
    }

    private Entry entry(ServerLevel level, Cell cell, long now) {
        var origin = new BlockPos(cell.x() * CELL_XZ, cell.y() * CELL_Y, cell.z() * CELL_XZ);
        if (!level.hasChunkAt(origin)) {
            cells.remove(cell);
            return null;
        }
        var entry = cells.get(cell);
        if (entry != null && now >= entry.checkedAt && now - entry.checkedAt < REFRESH_TICKS) return entry;
        if (entry == null) {
            if (cells.size() >= MAX_CELLS) cells.clear();
            entry = new Entry(now);
            cells.put(cell, entry);
        } else entry.refresh(now);
        return entry;
    }

    private static void inspect(ServerLevel level, Cell cell, Entry entry, int startX, int endX, int startY, int endY, int startZ, int endZ) {
        if (entry.knownLow == -1L && entry.knownHigh == 0xffffL) return;
        int originX = cell.x() * CELL_XZ, originY = cell.y() * CELL_Y, originZ = cell.z() * CELL_XZ;
        for (int x = startX; x <= endX; x++) for (int y = startY; y <= endY; y++) for (int z = startZ; z <= endZ; z++) {
            int index = ((x - originX) * CELL_Y + y - originY) * CELL_XZ + z - originZ;
            if (entry.known(index)) continue;
            entry.markKnown(index);
            var pos = new BlockPos(x, y, z);
            double strength = strength(level, pos);
            if (strength > 0) entry.sources.add(new Source(pos.immutable(), strength));
        }
    }

    private static double strength(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) return .9;
        if (state.is(Blocks.CAMPFIRE) && state.getValue(BlockStateProperties.LIT)) return 1.4;
        return 0;
    }

    private ThermalSourceCache() {}
}
