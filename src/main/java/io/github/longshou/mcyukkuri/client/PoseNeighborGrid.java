package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.pose.check.PoseGeometry;
import java.util.Arrays;

/** Indexes the interpolated feet used by the contact check, including actors outside the render list. */
final class PoseNeighborGrid {
    private static final int RANGE = PoseGeometry.PARTNER_BLOCKS;
    // The scan's double subtraction can round a slightly longer separation to exactly RANGE.
    private static final int CELL_SIZE = 2 * RANGE;
    private int[] heads = new int[16], next = new int[8], found = new int[8];
    private int[] cellX = new int[8], cellY = new int[8], cellZ = new int[8];
    private float[] x = new float[8], y = new float[8], z = new float[8];
    private long frame = Long.MIN_VALUE;
    private float partial;
    private double maxRadius;
    private int size;

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
        size = 0;
        maxRadius = 0;
    }

    void add(int actor, float x, float y, float z) {
        size++;
        this.x[actor] = x; this.y[actor] = y; this.z[actor] = z;
        int bucket = bucket(cellX[actor] = cell(x), cellY[actor] = cell(y), cellZ[actor] = cell(z));
        next[actor] = heads[bucket];
        heads[bucket] = actor;
    }

    int find(float x, float y, float z) {
        return find(x, y, z, RANGE);
    }

    void includeRadius(double radius) { maxRadius = Math.max(maxRadius, radius); }

    int findLoose(float x, float y, float z, double radius) {
        // Feet are indexed as floats, while the renderer interpolates the submitted faces in doubles.
        double rounding = Math.ulp(x) + Math.ulp(y) + Math.ulp(z);
        return find(x, y, z, radius + maxRadius + rounding);
    }

    private int find(float x, float y, float z, double range) {
        int count = 0;
        double search = Math.nextUp(range);
        int minX = cell(x - search), maxX = cell(x + search);
        int minY = cell(y - search), maxY = cell(y + search);
        int minZ = cell(z - search), maxZ = cell(z + search);
        // A scaled-up body can cover more cells than there are actors.
        if (range > RANGE && (double)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1) > size) {
            for (int actor = 0; actor < size; actor++) {
                if (inRange(actor, x, y, z, range)) found[count++] = actor;
            }
            return count;
        }
        for (int i = minX; i <= maxX; i++) for (int j = minY; j <= maxY; j++) for (int k = minZ; k <= maxZ; k++) {
            for (int actor = heads[bucket(i, j, k)]; actor >= 0; actor = next[actor]) {
                if (cellX[actor] != i || cellY[actor] != j || cellZ[actor] != k) continue;
                if (inRange(actor, x, y, z, range)) found[count++] = actor;
            }
        }
        return count;
    }

    private boolean inRange(int actor, float x, float y, float z, double range) {
        double dx = (double)this.x[actor] - x, dy = (double)this.y[actor] - y, dz = (double)this.z[actor] - z;
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    int actor(int index) { return found[index]; }

    private static int cell(double value) { return (int)Math.floor(value / CELL_SIZE); }

    private int bucket(int x, int y, int z) {
        int hash = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        return (hash ^ (hash >>> 16)) & (heads.length - 1);
    }
}
