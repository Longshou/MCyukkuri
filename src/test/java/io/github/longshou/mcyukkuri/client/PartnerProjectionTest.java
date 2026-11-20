package io.github.longshou.mcyukkuri.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartnerProjectionTest {
    @Test void reusesOnlyTheSameDrawClockAndBearing() {
        var reach = new PartnerProjection();
        assertFalse(reach.matches(10, .5f, .6, .8));
        reach.store(10, .5f, .6, .8, .43);
        assertTrue(reach.matches(10, .5f, .6, .8));
        assertEquals(.43, reach.reach());
        assertFalse(reach.matches(11, .5f, .6, .8));
        assertFalse(reach.matches(10, .75f, .6, .8));
        assertFalse(reach.matches(10, .5f, -.6, -.8));
        reach.store(10, .5f, -.6, -.8, .27);
        assertFalse(reach.matches(10, .5f, .6, .8));
        assertTrue(reach.matches(10, .5f, -.6, -.8));
        assertEquals(.27, reach.reach());
    }
}
