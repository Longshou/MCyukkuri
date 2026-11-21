package io.github.longshou.mcyukkuri.settlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreetDrawTest {
    @Test
    void daylightHoldsUntilTenThousandFadesAndStaysDarkThroughTheNight() {
        assertEquals(1, StreetDraw.daylight(0), 1e-9);
        assertEquals(1, StreetDraw.daylight(10000), 1e-9);
        assertEquals(.5, StreetDraw.daylight(11000), 1e-9);
        assertEquals(0, StreetDraw.daylight(12000), 1e-9);
        assertEquals(0, StreetDraw.daylight(18000), 1e-9);
        assertEquals(1, StreetDraw.daylight(24000 * 7L + 5000), 1e-9);
        assertEquals(.5, StreetDraw.daylight(24000 * 3L + 11000), 1e-9);
    }

    @Test
    void chanceRisesWithHungerNerveAndFriendlinessAndIsZeroAtNight() {
        assertEquals(.08 * .4 * .7, StreetDraw.chance(95, 0, 0, 1), 1e-9);
        assertEquals(.63, StreetDraw.chance(30, 100, 100, 1), 1e-9);
        assertEquals(0, StreetDraw.chance(30, 100, 100, 0), 1e-9);
        for (int b = 0; b <= 100; b += 10)
            for (int f = 0; f <= 100; f += 10)
                for (int n = 0; n <= 100; n += 5) {
                    double p = StreetDraw.chance(n, b, f, 1);
                    assertTrue(p > 0 && p <= .63 + 1e-9);
                    if (n >= 5) assertTrue(StreetDraw.chance(n - 5, b, f, 1) >= p);
                    if (b >= 10) assertTrue(StreetDraw.chance(n, b - 10, f, 1) <= p);
                    if (f >= 10) assertTrue(StreetDraw.chance(n, b, f - 10, 1) <= p);
                    assertEquals(p / 2, StreetDraw.chance(n, b, f, .5), 1e-9);
                }
    }

    @Test
    void spellLengthGrowsWithHungerFromTwentyToEightySeconds() {
        assertEquals(1200, StreetDraw.ticks(100));
        assertEquals(1200, StreetDraw.ticks(85));
        assertEquals(4800, StreetDraw.ticks(40));
        assertEquals(4800, StreetDraw.ticks(5));
        assertEquals(3000, StreetDraw.ticks(62.5f));
        for (int n = 5; n <= 100; n += 5) assertTrue(StreetDraw.ticks(n - 5) >= StreetDraw.ticks(n));
    }

    @Test
    void holderChanceStaysBetweenFourAndNineTenthsByDay() {
        assertEquals(.4, StreetDraw.holderChance(95, 100, 1), 1e-9);
        assertEquals(.4, StreetDraw.holderChance(30, 0, 1), 1e-9);
        assertEquals(.9, StreetDraw.holderChance(30, 100, 1), 1e-9);
        assertEquals(0, StreetDraw.holderChance(30, 100, 0), 1e-9);
        assertTrue(StreetDraw.holderChance(50, 70, 1) > StreetDraw.holderChance(50, 40, 1));
    }
}
