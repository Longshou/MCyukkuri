package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.pose.check.PoseGeometry;
import java.util.Arrays;

/** Indexes the interpolated feet used by the contact check, including actors outside the render list. */
final class PoseNeighborGrid {
    private static final int RANGE = PoseGeometry.PARTNER_BLOCKS;
    private static final int CELL_SIZE = 2 * RANGE;
    private int[] heads = new int[16], next = new int[8], found = new int[8];
    private int[] cellX = new int[8], cellY = new int[8], cellZ = new int[8];
    private float[] x = new float[8], y = new float[8], z = new float[8];
    private long frame = Long.MIN_VALUE;
    private float partial;

    boolean matches(long frame, float partial) { return this.frame == frame && this.partial == partial; }

    void invalidate() { frame = Long.MIN_VALUE; }

    void reserve(int actors) {
        if (actors <= next.length) return;
        int size = next.length * 2;
        heads = new int[size * 2];
        next = new int[size]; found = new int[size];
        cellX = new int[size]; cellY = new int[size]; cellZ = new int[size];
        x = new float[size]; y = new float[size]; z = new float[size];
    }

    void begin(long frame, float partial) {
        this.frame = frame;
        this.partial = partial;
        Arrays.fill(heads, -1);
    }

    void add(int actor, float x, float y, float z) {
        this.x[actor] = x; this.y[actor] = y; this.z[actor] = z;
        int bucket = bucket(cellX[actor] = cell(x), cellY[actor] = cell(y), cellZ[actor] = cell(z));
        next[actor] = heads[bucket];
        heads[bucket] = actor;
    }

    int find(float x, float y, float z) {
        int count = 0;
        int minX = cell((double)x - RANGE), maxX = cell((double)x + RANGE);
        int minY = cell((double)y - RANGE), maxY = cell((double)y + RANGE);
        int minZ = cell((double)z - RANGE), maxZ = cell((double)z + RANGE);
        for (int i = minX; i <= maxX; i++) for (int j = minY; j <= maxY; j++) for (int k = minZ; k <= maxZ; k++) {
            for (int actor = heads[bucket(i, j, k)]; actor >= 0; actor = next[actor]) {
                if (cellX[actor] != i || cellY[actor] != j || cellZ[actor] != k) continue;
                double dx = (double)this.x[actor] - x, dy = (double)this.y[actor] - y, dz = (double)this.z[actor] - z;
                if (dx * dx + dy * dy + dz * dz <= RANGE * RANGE) found[count++] = actor;
            }
        }
        return count;
    }

    int actor(int index) { return found[index]; }

    private static int cell(double value) { return (int)Math.floor(value / CELL_SIZE); }

    private int bucket(int x, int y, int z) {
        int hash = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        return (hash ^ (hash >>> 16)) & (heads.length - 1);
    }
}
