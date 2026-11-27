package io.github.longshou.mcyukkuri.status;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RainShelterTest {
    @Test void toleranceIsAnIntervalShiftedByBoldnessAndIntelligence() {
        assertEquals(.5f, RainShelter.tolerance(20, 20), 1e-6);
        assertEquals(5.5f, RainShelter.tolerance(80, 20), 1e-6);
        assertTrue(RainShelter.tolerance(20, 50) < RainShelter.tolerance(50, 50));
        assertTrue(RainShelter.tolerance(50, 50) < RainShelter.tolerance(80, 50));
        assertTrue(RainShelter.tolerance(80, 80) < RainShelter.tolerance(80, 20));
    }

    @Test void presentationIsTheSharedRainRun() {
        assertEquals("teary", RainShelter.face(20));
        assertEquals("neutral", RainShelter.face(75));
        assertEquals("rain_run", RainShelter.line());
    }
}
