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
        var beat = new ExcitedLeak.Beat(10, 18, 58, 138, .5f, 20, false);
        assertEquals("leak_startle", beat.face(10));
        assertEquals("leak_flow", beat.face(18));
        assertEquals("side_teary", beat.face(58));
        var loaded = new ExcitedLeak.Beat(beat.start(), beat.flowAt(), beat.puddleAt(), beat.endAt(), beat.width(), beat.amount(), beat.joy());
        assertEquals(20, loaded.amount());
        assertEquals(beat.face(57), loaded.face(57));
        assertEquals(beat.face(59), loaded.face(59));
        assertEquals("", loaded.face(138));
        assertTrue(loaded.cooling(1209));
        assertFalse(loaded.cooling(1210));
    }

    @Test void seededRollSuppliesTheGameBeatAndTraceFade() {
        var drop = ExcitedLeak.roll("ADULT", 50, ExcitedLeak.Stimulus.DROP, 1, 40, 65, .8f);
        assertEquals(.1f, drop.chance(), .00001f);
        assertEquals(48, drop.beat().flowAt());
        assertEquals(78, drop.beat().puddleAt());
        assertEquals(171, drop.beat().endAt());
        assertEquals(.48f, drop.beat().width(), .00001f);
        assertEquals(15, drop.beat().amount());
        assertNull(ExcitedLeak.roll("ADULT", 50, ExcitedLeak.Stimulus.SCARE, 1, 40, 65, .8f).beat());
        var joy = ExcitedLeak.roll("BABY", 50, ExcitedLeak.Stimulus.JOY, 1, 0, 65, .1f).beat();
        assertEquals("satisfied", joy.face(joy.puddleAt()));
        assertEquals(255, ExcitedLeak.opacity(600));
        assertEquals(128, ExcitedLeak.opacity(700));
        assertEquals(0, ExcitedLeak.opacity(800));
    }
}
