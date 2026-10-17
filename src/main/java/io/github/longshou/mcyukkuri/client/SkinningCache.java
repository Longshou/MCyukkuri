package io.github.longshou.mcyukkuri.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import org.joml.Vector3f;

/** Reuses identical corner transforms within one posed draw, never between actors or frames. */
final class SkinningCache {
    // UVs and face normals deliberately stay on the original corners. Only position is shared.
    record Key(float x, float y, float z, int first, int second, float weight, int feature,
               float puffX, float puffY, float puffZ) {}

    private final int[] slots;
    private final int[] stamps;
    private final float[] positions;
    private int generation;
    private boolean active;

    SkinningCache(int vertexCount, IntFunction<Key> keyAt) {
        slots = new int[vertexCount];
        var shared = new HashMap<Key, Integer>();
        int count = 0;
        for (int i = 0; i < vertexCount; i++) {
            Key key = keyAt.apply(i);
            // Expression patches may move matching rest-space corners independently.
            Integer slot = key == null ? null : shared.get(key);
            if (slot == null) {
                slot = count++;
                if (key != null) shared.put(key, slot);
            }
            slots[i] = slot;
        }
        stamps = new int[count];
        positions = new float[count * 3];
    }

    void begin() {
        if (generation == Integer.MAX_VALUE) {
            Arrays.fill(stamps, 0);
            generation = 0;
        }
        generation++;
        active = true;
    }

    /** Load-time selection for reductions over a subset of the mesh (e.g. its body floor).
     *  Reuse the deformation keys, never a posed result: callers may change bones each pass. */
    int[] uniqueIndices(IntPredicate included) {
        boolean[] selected = new boolean[stamps.length];
        int[] indices = new int[stamps.length];
        int count = 0;
        for (int index = 0; index < slots.length; index++) {
            int slot = slots[index];
            if (included.test(index) && !selected[slot]) {
                selected[slot] = true;
                indices[count++] = index;
            }
        }
        return Arrays.copyOf(indices, count);
    }

    void end() {
        active = false;
    }

    boolean read(int index, Vector3f target) {
        if (!active) return false;
        int slot = slots[index];
        if (stamps[slot] != generation) return false;
        int offset = slot * 3;
        target.set(positions[offset], positions[offset + 1], positions[offset + 2]);
        return true;
    }

    void write(int index, Vector3f point) {
        if (!active) return;
        int slot = slots[index], offset = slot * 3;
        positions[offset] = point.x;
        positions[offset + 1] = point.y;
        positions[offset + 2] = point.z;
        stamps[slot] = generation;
    }
}
