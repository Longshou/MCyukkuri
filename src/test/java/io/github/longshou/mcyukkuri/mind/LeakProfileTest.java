package io.github.longshou.mcyukkuri.mind;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LeakProfileTest {
    @Test void fullnessScalesBothEndsOfEveryStageBand() {
        float[][] bands = {{.5f, .8f, .2f, .4f}, {.25f, .45f, .05f, .15f}, {.05f, .15f, 0, 0}};
        for (var stage : LeakProfile.values()) {
            int i = 0;
            for (boolean strong : new boolean[]{true, false}) {
                for (int control : new int[]{100, 0}) {
                    float max = bands[stage.ordinal()][i++];
                    assertEquals(0f, stage.chance(strong, control, 0));
                    assertEquals(max / 2, stage.chance(strong, control, 32.5f), .00001);
                    assertEquals(max, stage.chance(strong, control, 65), .00001);
                    assertEquals(max, stage.chance(strong, control, 100), .00001);
                }
            }
        }
    }

    @Test void adultRollCanMissAtFullPressureAndAnEmptyBodyCannotEmit() {
        float chance = LeakProfile.ADULT.chance(true, 0, 65);
        assertTrue(new LeakProfile.Draw(.149f, chance).wins());
        assertFalse(new LeakProfile.Draw(chance, chance).wins());
        assertFalse(new LeakProfile.Draw(.9f, chance).wins());
        assertFalse(new LeakProfile.Draw(0, LeakProfile.ADULT.chance(false, 0, 100)).wins());
        assertFalse(new LeakProfile.Draw(0, LeakProfile.BABY.chance(true, 0, 0)).wins());
        float full = LeakProfile.ADULT.portion(0, 65);
        assertEquals(20, full);
        assertEquals(2, LeakProfile.ADULT.portion(0, 2));
        assertTrue(LeakProfile.ADULT.width(0, .75f, 2) < LeakProfile.ADULT.width(0, .75f, full));
    }

    @Test void restoredBeatObservesTheOriginalEmissionAndCooldownDeadlines() {
        var beat = new LeakProfile.Beat(10, 18, 58, 138, .5f, 20, false);
        assertEquals("leak_startle", beat.face(10));
        assertEquals("leak_flow", beat.face(18));
        assertEquals("side_teary", beat.face(58));
        var loaded = new LeakProfile.Beat(beat.start(), beat.flowAt(), beat.puddleAt(), beat.endAt(), beat.width(), beat.amount(), beat.joy());
        assertEquals(20, loaded.amount());
        assertEquals(beat.face(57), loaded.face(57));
        assertEquals(beat.face(59), loaded.face(59));
        assertEquals("", loaded.face(138));
        assertTrue(loaded.cooling(1209));
        assertFalse(loaded.cooling(1210));
    }
}
