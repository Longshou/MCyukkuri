package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.SprintStamina.Build;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SprintStaminaTest {
    @ParameterizedTest
    @CsvSource({"REIMU,85,90", "ALICE,85,90", "MARISA,115,85", "MYON,140,80",
            "HOUSE_REIMU,58,95", "HOUSE_MARISA,58,95", "CHEN,65,50", "PATCHOULI,38,115",
            "REMILIA,680,110", "FLANDRE,680,110"})
    void speciesBurstAndRest(YukkuriVariant kind, int burst, int recovery) {
        var build = Build.of(kind);
        var reserve = new SprintStamina();
        double capacity = SprintStamina.capacity(build, 1, 1, 1, 100, false);
        assertEquals(burst, capacity);
        for (int tick = 1; tick < burst; tick++) reserve.run(capacity);
        assertFalse(reserve.resting());
        assertTrue(reserve.pace(kind.isPredator()) < 1);
        // Floating-point addition can take one extra tick to fill the last fraction.
        reserve.run(capacity);
        if (!reserve.resting()) reserve.run(capacity);
        assertTrue(reserve.resting());
        assertEquals(kind.isPredator() ? .5 : 0, reserve.pace(kind.isPredator()));
        int ticks = 0;
        while (reserve.resting() && ticks < recovery) { reserve.recover(build); ticks++; }
        assertEquals(.8 * recovery, ticks, 1);
        assertFalse(reserve.resting());
        assertEquals(1, reserve.pace(kind.isPredator()));
    }

    @Test void individualConditionAndFormShiftTheSameReserve() {
        double fit = SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 100, false);
        assertAll(
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, .7, 1, 1, 100, false) < fit),
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, .55, .4, 100, false) < fit),
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, 1, 1.5, 100, false) < fit),
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 20, false) < fit),
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 100, true) < fit));
    }

    @Test void partialRestAndReloadRetainTheSameNextStride() {
        var reserve = new SprintStamina();
        reserve.run(1);
        for (int tick = 0; tick < 40; tick++) reserve.recover(Build.ORDINARY);
        var loaded = new SprintStamina();
        loaded.restore(reserve.debt(), reserve.resting());
        assertTrue(loaded.resting());
        for (int tick = 0; tick < 90; tick++) {
            reserve.recover(Build.ORDINARY);
            loaded.recover(Build.ORDINARY);
            assertEquals(reserve.pace(false), loaded.pace(false));
        }
        assertEquals(0, loaded.debt());
    }
}
