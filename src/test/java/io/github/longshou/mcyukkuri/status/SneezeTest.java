package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.status.SneezeRules.Trigger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SneezeTest {
    @Test void susceptibilityShiftsWithAgeFrailtyAndHealth() {
        double healthyAdult = SneezeRules.susceptibility(false, false, false, false, false, 1.0);
        double juvenile = SneezeRules.susceptibility(false, true, false, false, false, 1.0);
        double baby = SneezeRules.susceptibility(true, false, false, false, false, 1.0);
        double frailAdult = SneezeRules.susceptibility(false, false, true, false, false, 1.0);
        double patchouli = SneezeRules.susceptibility(false, false, false, false, true, 1.0);
        double extreme = SneezeRules.susceptibility(true, false, true, true, true, 0.0);

        assertEquals(SneezeRules.MIN_SUSCEPTIBILITY, healthyAdult, 1e-9);
        assertTrue(juvenile > healthyAdult);
        assertTrue(baby > juvenile);
        assertTrue(frailAdult > healthyAdult);
        assertTrue(patchouli > healthyAdult);
        assertEquals(SneezeRules.MAX_SUSCEPTIBILITY, extreme, 1e-9);
    }

    @Test void chanceRespectsBoundsAndZeroStimulus() {
        assertEquals(0.0, SneezeRules.chance(Trigger.DUST, 0.0, 1.5), 1e-9);
        assertEquals(0.0, SneezeRules.chance(Trigger.COLD, -0.5, 1.5), 1e-9);
        assertEquals(0.0, SneezeRules.chance(null, 1.0, 1.5), 1e-9);
        assertEquals(0.0, SneezeRules.chance(Trigger.DUST, Double.NaN, 1.0), 1e-9);

        double adultDust = SneezeRules.chance(Trigger.DUST, 0.5, 1.0);
        double babyDust = SneezeRules.chance(Trigger.DUST, 0.5, 1.45);
        assertTrue(babyDust > adultDust);
        assertTrue(SneezeRules.chance(Trigger.DUST, 1.0, 10.0) <= SneezeRules.DUST_MAX_CHANCE);
        assertTrue(SneezeRules.chance(Trigger.COLD, 1.0, 10.0) <= SneezeRules.COLD_MAX_CHANCE);
    }

    @Test void cooldownPreventsLoopsAndScalesWithinFloor() {
        int minAdult = SneezeRules.cooldownTicks(1.0, 0.0);
        int maxAdult = SneezeRules.cooldownTicks(1.0, 1.0);
        int minFrailBaby = SneezeRules.cooldownTicks(SneezeRules.MAX_SUSCEPTIBILITY, 0.0);
        int maxFrailBaby = SneezeRules.cooldownTicks(SneezeRules.MAX_SUSCEPTIBILITY, 1.0);

        assertEquals(SneezeRules.BASE_MIN_COOLDOWN, minAdult);
        assertEquals(SneezeRules.BASE_MAX_COOLDOWN, maxAdult);
        assertTrue(minFrailBaby >= SneezeRules.MIN_COOLDOWN);
        assertTrue(minFrailBaby < minAdult);
        assertTrue(maxFrailBaby < maxAdult);

        long start = 1000L;
        long nextAllowed = start + minFrailBaby;
        assertFalse(SneezeRules.ready(start, nextAllowed));
        assertFalse(SneezeRules.ready(nextAllowed - 1, nextAllowed));
        assertTrue(SneezeRules.ready(nextAllowed, nextAllowed));
    }

    @Test void dustLevelIsZeroInCleanRoomAndPositiveWithBuildupOrLint() {
        assertEquals(0.0, SneezeRules.dustLevel(0, 600, 0, 2), 1e-9);
        assertEquals(0.0, SneezeRules.dustLevel(199, 600, 0, 2), 1e-9);
        assertTrue(SneezeRules.dustLevel(200, 600, 0, 2) > 0.0);
        assertEquals(0.5, SneezeRules.dustLevel(0, 600, 1, 2), 1e-9);
        assertEquals(1.0, SneezeRules.dustLevel(0, 600, 3, 2), 1e-9);
    }

    @Test void coldIntensityCoversColdAndWetChilledStates() {
        assertEquals(0.0, SneezeRules.coldIntensity(false, 0.0, 0.8, 0.2, 0.0), 1e-9);
        assertEquals(0.0, SneezeRules.coldIntensity(false, 0.0, 0.8, 0.2, 60.0), 1e-9);
        double wetChilled = SneezeRules.coldIntensity(false, 0.0, 0.3, 0.2, 50.0);
        double mildCold = SneezeRules.coldIntensity(true, 0.05, 0.15, 0.2, 0.0);
        double deepColdWet = SneezeRules.coldIntensity(true, 0.6, -0.4, 0.2, 80.0);

        assertTrue(wetChilled > 0.0);
        assertTrue(mildCold >= 0.35);
        assertEquals(1.0, deepColdWet, 1e-9);
    }
}
