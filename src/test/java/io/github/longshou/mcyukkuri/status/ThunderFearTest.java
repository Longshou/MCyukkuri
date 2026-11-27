package io.github.longshou.mcyukkuri.status;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ThunderFearTest {
    @Test void neverHeardOrReloadedIsNeverFrightened() {
        assertFalse(ThunderFear.active(1000, Long.MIN_VALUE / 2, true, 20));
        assertFalse(ThunderFear.active(1000, Long.MIN_VALUE, true, 20));
        assertFalse(ThunderFear.active(500, 600, true, 20));
    }

    @Test void timidHoldsLongerThanBoldAndBothCloseWhenStormPasses() {
        int boldHold = ThunderFear.clapHold(80);
        int midHold = ThunderFear.clapHold(50);
        int timidHold = ThunderFear.clapHold(20);
        assertTrue(boldHold < midHold && midHold < timidHold);
        assertEquals(80, boldHold);
        assertEquals(200, timidHold);

        long clap = 340;
        // At 150 ticks after clap with storm cleared: bold (clapHold=90) is calm, timid (clapHold=200) still hides.
        assertFalse(ThunderFear.active(clap + 150, clap, false, 75));
        assertTrue(ThunderFear.active(clap + 150, clap, false, 20));
        // Once timid's hold elapses with storm cleared, it closes too.
        assertFalse(ThunderFear.active(clap + timidHold, clap, false, 20));
    }

    @Test void endlessStormStillCapsByBoldness() {
        long clap = 100;
        int boldCap = ThunderFear.stormCap(80);
        int timidCap = ThunderFear.stormCap(20);
        assertTrue(boldCap < timidCap);
        assertTrue(ThunderFear.active(clap + boldCap - 1, clap, true, 80));
        assertFalse(ThunderFear.active(clap + boldCap, clap, true, 80));
        assertTrue(ThunderFear.active(clap + timidCap - 1, clap, true, 20));
        assertFalse(ThunderFear.active(clap + timidCap, clap, true, 20));
    }

    @Test void ashoreNeedAndPresentationScaleWithBoldness() {
        assertEquals(60.0, ThunderFear.ashoreNeed(60, 20), 1e-6);
        assertTrue(ThunderFear.ashoreNeed(60, 50) > 30.0);
        assertTrue(ThunderFear.ashoreNeed(60, 80) < 30.0);

        assertEquals("horrified", ThunderFear.hideFace(20));
        assertEquals("afraid", ThunderFear.hideLine(20));
        assertEquals("teary", ThunderFear.hideFace(50));
        assertEquals("thunder", ThunderFear.hideLine(50));
        assertEquals("surprise", ThunderFear.hideFace(75));
        assertEquals("thunder", ThunderFear.hideLine(75));

        assertEquals("closed_smile", ThunderFear.calmFace(20));
        assertEquals("closed_smile", ThunderFear.calmFace(50));
        assertEquals("happy", ThunderFear.calmFace(75));
    }
}
