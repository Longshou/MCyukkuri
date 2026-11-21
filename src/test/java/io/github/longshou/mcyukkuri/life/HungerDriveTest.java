package io.github.longshou.mcyukkuri.life;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HungerDriveTest {
    @Test
    void fedBodiesKeepTheOrdinarySearchAndStarvingOnesDoubleIt() {
        assertEquals(1, HungerDrive.reachScale(60), 1e-9);
        assertEquals(1, HungerDrive.reachScale(30), 1e-9);
        assertEquals(1.5, HungerDrive.reachScale(15), 1e-9);
        assertEquals(2, HungerDrive.reachScale(0), 1e-9);
        assertEquals(3, HungerDrive.sweepAttempts(50));
        assertEquals(8, HungerDrive.sweepAttempts(0));
    }

    @Test
    void noticeRangeGrowsWithHungerAndBoldnessAndShrinksInTheDark() {
        assertEquals(8, HungerDrive.noticeRange(75, 50, false), 1e-9);
        assertEquals(8, HungerDrive.noticeRange(90, 50, false), 1e-9);
        assertEquals(20, HungerDrive.noticeRange(15, 50, false), 1e-9);
        assertEquals(20, HungerDrive.noticeRange(0, 50, false), 1e-9);
        assertEquals(14, HungerDrive.noticeRange(45, 50, false), 1e-9);
        assertTrue(HungerDrive.noticeRange(45, 80, false) > HungerDrive.noticeRange(45, 50, false));
        assertTrue(HungerDrive.noticeRange(45, 20, false) < HungerDrive.noticeRange(45, 50, false));
        assertEquals(.6 * 14, HungerDrive.noticeRange(45, 50, true), 1e-9);
        assertEquals(4, HungerDrive.noticeRange(75, 0, true), 1e-9);
        for (int n = 0; n <= 100; n += 5)
            for (int b = 0; b <= 100; b += 10) {
                double day = HungerDrive.noticeRange(n, b, false);
                assertTrue(day >= 4 && day <= 26.1);
                assertTrue(HungerDrive.noticeRange(n, b, true) <= day);
                if (n >= 5) assertTrue(HungerDrive.noticeRange(n - 5, b, false) >= day);
            }
    }

    @Test
    void sleeperWakesForHungerByRangeFromTwentyToThirty() {
        assertEquals(20, HungerDrive.wakeLevel(0), 1e-6);
        assertEquals(30, HungerDrive.wakeLevel(100), 1e-6);
        assertTrue(HungerDrive.wakeLevel(40) < HungerDrive.wakeLevel(90));
    }

    @Test
    void refusedFoodIsLeftAloneUntilExpiryOrTheBodyMovesOn() {
        var memory = new HungerDrive.Rejections();
        var item = UUID.randomUUID();
        memory.reject(item, 100, 0, 0);
        assertTrue(memory.rejected(item, 150, 2, 2));
        assertFalse(memory.rejected(item, 100 + HungerDrive.REJECT_TICKS, 2, 2));
        memory.reject(item, 100, 0, 0);
        assertFalse(memory.rejected(item, 150, 20, 0));
        assertFalse(memory.rejected(item, 151, 0, 0));
    }

    @Test
    void memoryStaysBounded() {
        var memory = new HungerDrive.Rejections();
        var first = UUID.randomUUID();
        memory.reject(first, 0, 0, 0);
        for (int i = 0; i < 40; i++) memory.reject(UUID.randomUUID(), i, 0, 0);
        var last = UUID.randomUUID();
        memory.reject(last, 50, 0, 0);
        assertTrue(memory.rejected(last, 51, 0, 0));
    }
}
