package io.github.longshou.mcyukkuri.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.longshou.mcyukkuri.pose.PaperTent;
import org.junit.jupiter.api.Test;

class PaperFitSamplesTest {
    private final PaperFitSamples<Integer> samples = new PaperFitSamples<>();
    private final PaperTent.Grid first = new PaperTent.Grid(2, 2, new float[2][2][3], new float[2][2][3]);

    @Test
    void keepsEveryDrawnWearerAcrossFramesAndDrawOrderChanges() {
        for (int wearer = 1; wearer <= 65; wearer++) samples.put(wearer, 0, first, 1);
        for (long frame = 2; frame <= 3; frame++) {
            samples.expireBefore(frame - 1);
            for (int wearer = 65; wearer >= 1; wearer--) {
                var fit = samples.get(wearer, frame);
                assertEquals(0, fit.inputs);
                assertSame(first, fit.grid);
            }
        }
        var next = new PaperTent.Grid(2, 3, new float[2][3][3], new float[2][3][3]);
        samples.put(65, 1, next, 4);
        assertEquals(1, samples.get(65, 4).inputs);
        assertSame(next, samples.get(65, 4).grid);
    }

    @Test
    void releasesUndrawnWearersAndClearsLevelState() {
        samples.put(1, 0, first, 1);
        samples.put(2, 0, first, 1);
        samples.expireBefore(1);
        assertSame(first, samples.get(1, 2).grid);

        samples.expireBefore(2);
        assertNull(samples.get(2, 3));
        assertSame(first, samples.get(1, 3).grid);
        samples.remove(1);
        assertNull(samples.get(1, 3));
        samples.put(3, 0, first, 3);
        samples.clear();
        assertNull(samples.get(3, 3));
    }
}
