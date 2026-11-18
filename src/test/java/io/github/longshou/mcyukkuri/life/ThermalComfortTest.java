package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThermalComfortTest {
    @Test void exposureTracksTheFeltDeficitAndAge() {
        assertEquals(1, ThermalComfort.exposureRate(-.8, .2, 1), 1e-9);
        assertEquals(.5, ThermalComfort.exposureRate(-.3, .2, 1), 1e-9);
        assertEquals(.1, ThermalComfort.exposureRate(.15, .2, 2), 1e-9);
        assertEquals(0, ThermalComfort.exposureRate(.2, .2, 1), 1e-9);
        assertEquals(-.2, ThermalComfort.exposureRate(.3, .2, 2), 1e-9);
        assertEquals(-2, ThermalComfort.exposureRate(2, .2, 1), 1e-9);
    }

    @Test void refugeNeedsMeaningfulGainAndMustNotOverheat() {
        assertFalse(ThermalComfort.warmerRefuge(-.5, -.36, 1.5));
        assertTrue(ThermalComfort.warmerRefuge(-.5, -.35, 1.5));
        assertTrue(ThermalComfort.warmerRefuge(-.5, .2, 1.5));
        assertFalse(ThermalComfort.warmerRefuge(.1, 1.5, 1.5));
    }
}
