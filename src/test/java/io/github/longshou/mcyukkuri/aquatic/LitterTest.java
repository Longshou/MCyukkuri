package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.family.Litter;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LitterTest {
    @Test void aquaticLitterIsOneToThreeAndMostlyOneOrTwo() {
        var random = new Random(7);
        int[] counts = new int[4];
        for (int i = 0; i < 100_000; i++) counts[Litter.size(.5, true, random)]++;
        assertEquals(0, counts[0]);
        assertTrue(counts[3] > 4_000 && counts[3] < 8_000, "third young about 6%: " + counts[3]);
        assertTrue(counts[1] + counts[2] > 90_000);
    }
    @Test void landLitterKeepsFourDraws() {
        var random = new Random(7);
        int most = 0;
        for (int i = 0; i < 1_000; i++) most = Math.max(most, Litter.size(.9, false, random));
        assertEquals(5, most);
    }
}
