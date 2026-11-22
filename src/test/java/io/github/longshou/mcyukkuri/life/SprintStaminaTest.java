package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.SprintStamina.Build;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SprintStaminaTest {
    private static final double AVERAGE = SprintStamina.constitution(50, 50);

    @ParameterizedTest
    @CsvSource({"REIMU,170,225", "ALICE,170,225", "MARISA,230,215", "MYON,280,200",
            "HOUSE_REIMU,116,240", "HOUSE_MARISA,116,240", "CHEN,130,125", "PATCHOULI,76,290",
            "REMILIA,1360,275", "FLANDRE,1360,275"})
    void speciesBurstAndRest(YukkuriVariant kind, int burst, int recovery) {
        var build = Build.of(kind);
        var reserve = new SprintStamina();
        double capacity = SprintStamina.capacity(build, 1, 1, 1, 100, false, 1);
        assertEquals(burst, capacity);
        for (int tick = 1; tick < burst; tick++) reserve.run(capacity);
        assertFalse(reserve.resting());
        assertTrue(reserve.pace() < 1);
        // Floating-point addition can take one extra tick to fill the last fraction.
        reserve.run(capacity);
        if (!reserve.resting()) reserve.run(capacity);
        assertTrue(reserve.resting());
        assertEquals(.5, reserve.pace());
        double rest = SprintStamina.restTicks(build, 1, 1);
        assertEquals(recovery, rest);
        int ticks = 0;
        while (reserve.resting() && ticks < 2 * recovery) { reserve.recover(rest); ticks++; }
        assertEquals(.85 * recovery, ticks, 1);
        assertFalse(reserve.resting());
        assertEquals(1, reserve.pace());
    }

    @Test void everyBuildRestsAtLeastSixSecondsOnceSpent() {
        // A long rest with a low resume line keeps a body from flipping between spent and recovered.
        for (var build : Build.values()) assertTrue(.85 * SprintStamina.restTicks(build, 1, 1) >= 100, build.toString());
    }

    @Test void individualConditionAndFormShiftTheSameReserve() {
        double fit = SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 100, false, 1);
        assertAll(
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, .7, 1, 1, 100, false, 1) < fit),
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, .55, .4, 100, false, 1) < fit),
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, 1, 1.5, 100, false, 1) < fit),
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 20, false, 1) < fit),
                () -> assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 100, true, 1) < fit));
    }

    @Test void constitutionFollowsTheTwoInheritedAxesWithinItsInterval() {
        assertEquals(1, AVERAGE, 1e-9);
        assertEquals(.8, SprintStamina.constitution(0, 0), 1e-9);
        assertEquals(1.2, SprintStamina.constitution(100, 100), 1e-9);
        assertTrue(SprintStamina.constitution(70, 60) > SprintStamina.constitution(30, 40));
        // A hardier body lasts longer and rests sooner; a young one is spent sooner and rests sooner.
        var strong = SprintStamina.constitution(80, 80);
        var weak = SprintStamina.constitution(20, 20);
        assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 100, false, strong)
                > SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 100, false, weak));
        assertTrue(SprintStamina.restTicks(Build.ORDINARY, 1, strong) < SprintStamina.restTicks(Build.ORDINARY, 1, weak));
        assertTrue(SprintStamina.restTicks(Build.ORDINARY, .5, 1) < SprintStamina.restTicks(Build.ORDINARY, 1, 1));
        assertTrue(SprintStamina.capacity(Build.ORDINARY, 1, .5, 1, 100, false, 1)
                < SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 100, false, 1));
    }

    @Test void aSpentBodyStaysSpentUntilWellRestedAndIsNotSpentAgainAtOnce() {
        double capacity = SprintStamina.capacity(Build.ORDINARY, 1, 1, 1, 100, false, 1);
        double rest = SprintStamina.restTicks(Build.ORDINARY, 1, 1);
        var reserve = new SprintStamina();
        for (int tick = 0; tick < capacity + 2 && !reserve.resting(); tick++) reserve.run(capacity);
        assertTrue(reserve.resting());
        // Halfway through the rest the body still catches its breath.
        for (int tick = 0; tick < rest / 2; tick++) reserve.recover(rest);
        assertTrue(reserve.resting());
        while (reserve.resting()) reserve.recover(rest);
        // Having resumed, it needs most of a full burst to run out again.
        int ticks = 0;
        while (!reserve.resting()) { reserve.run(capacity); ticks++; }
        assertTrue(ticks > .8 * capacity, "ran out again after " + ticks);
    }

    @Test void partialRestAndReloadRetainTheSameNextStride() {
        var reserve = new SprintStamina();
        reserve.run(1);
        double rest = SprintStamina.restTicks(Build.ORDINARY, 1, 1);
        for (int tick = 0; tick < 40; tick++) reserve.recover(rest);
        var loaded = new SprintStamina();
        loaded.restore(reserve.debt(), reserve.resting());
        assertTrue(loaded.resting());
        for (int tick = 0; tick < 300; tick++) {
            reserve.recover(rest);
            loaded.recover(rest);
            assertEquals(reserve.pace(), loaded.pace());
        }
        assertEquals(0, loaded.debt());
    }
}
