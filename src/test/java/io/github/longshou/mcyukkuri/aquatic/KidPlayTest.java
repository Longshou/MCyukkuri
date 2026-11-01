package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KidPlayTest {
    @Test void fallingUsesTheAuthoredHardRockPhase() {
        assertTrue(HullPresentation.brimFall("ROCKING"));
        assertFalse(HullPresentation.brimFall("UPRIGHT"));
        assertFalse(HullPresentation.brimFall("FLIPPING"));
    }
    @Test void paddlingWinsOverFoodForAJumper() {
        assertEquals(Behaviors.KID_MIMIC_PADDLE, KidPlayRule.pick(true, true, 4, 0, .5));
    }
    @Test void foodDrawChangesOnlyPlayAndNeverPermitsInfantSplashing() {
        assertFalse(KidPlayRule.available(false, 0));
        assertTrue(KidPlayRule.available(false, 1));
        assertTrue(KidPlayRule.available(true, 0));
        assertEquals(Behaviors.KID_POKE_STORES, KidPlayRule.pick(true, false, 4, .49, .5));
        assertEquals(Behaviors.KID_BRIM_SPLASH, KidPlayRule.pick(true, false, 4, .5, .5));
        assertEquals(Behaviors.KID_BRIM_SPLASH, KidPlayRule.pick(true, false, 0, 0, .5));
        assertEquals(Behaviors.KID_POKE_STORES, KidPlayRule.pick(false, true, 4, .99, .5));
        assertNull(KidPlayRule.pick(false, false, 0, 0, .5));
    }
    @Test void brimAndHideUseTheSameAuthoredRingAndInterpolatedUnderside() {
        var splash = HullBounds.brim(.3, -Math.PI / 2);
        var hide = HullBounds.brim(.3, 0);
        assertEquals(0, splash.x(), 1e-12);
        assertEquals(-hide.x(), splash.z(), 1e-12);
        assertEquals(0, hide.z(), 1e-12);
        assertEquals(-.0095, hide.y(), 1e-12);
        assertEquals(-.0033, HullBounds.brim(.45, 0).y(), 1e-12);
        assertEquals((-.0095 - .0033) / 2, HullBounds.brim(.375, 0).y(), 1e-12);
        assertTrue(hide.x() > HullBounds.LEVEL_RADIUS && hide.x() < HullBounds.RADIUS);
    }
}
