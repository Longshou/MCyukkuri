package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.pose.PaperTent;
import java.util.LinkedHashMap;

/** Holds one sampled fold per recently drawn wearer. Its limit follows the drawn population. */
final class PaperFitSamples<K> {
    static final class Sample {
        final long inputs;
        final PaperTent.Grid grid;
        long frame;

        Sample(long inputs, PaperTent.Grid grid, long frame) {
            this.inputs = inputs;
            this.grid = grid;
            this.frame = frame;
        }
    }

    private final LinkedHashMap<K,Sample> samples = new LinkedHashMap<>(16,.75f,true);

    Sample get(K wearer, long frame) {
        var sample = samples.get(wearer);
        if (sample != null) sample.frame = frame;
        return sample;
    }

    void put(K wearer, long inputs, PaperTent.Grid grid, long frame) {
        samples.put(wearer, new Sample(inputs, grid, frame));
    }

    void expireBefore(long frame) {
        if (samples.isEmpty()) return;
        for (var it = samples.values().iterator(); it.hasNext();) {
            if (it.next().frame >= frame) break;
            it.remove();
        }
    }

    void remove(K wearer) {
        samples.remove(wearer);
    }

    void clear() {
        samples.clear();
    }
}
