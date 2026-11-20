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

    @Test void radiantHeatFallsOffAndSlowsColdExposureWithinReach() {
        double near = ThermalComfort.radiantWarmth(2.2, 4, 0);
        double far = ThermalComfort.radiantWarmth(2.2, 16, 2);
        assertEquals(2.2 * 2 / 3, near, 1e-9);
        assertEquals(2.2 / 3, far, 1e-9);
        assertEquals(0, ThermalComfort.radiantWarmth(2.2, 17, 0));
        assertEquals(0, ThermalComfort.radiantWarmth(2.2, 9, -3));
        assertTrue(ThermalComfort.exposureRate(-1.6 + near, .2, 2) < ThermalComfort.exposureRate(-1.6 + far, .2, 2));
        assertTrue(ThermalComfort.exposureRate(-1.6 + far, .2, 2) < ThermalComfort.exposureRate(-1.6, .2, 2));
    }

    @Test void firePreferenceRespondsToColdBoldnessAndPriorBurns() {
        double timid = ThermalComfort.preferredFireDistance(.2, .6, .3, false, false, false);
        double bold = ThermalComfort.preferredFireDistance(.8, .6, .3, false, false, false);
        double colder = ThermalComfort.preferredFireDistance(.8, .6, 1.2, false, false, false);
        assertTrue(timid > bold && bold > colder);
        assertTrue(ThermalComfort.preferredFireDistance(.8, .6, 1.2, true, false, false) > timid);
        assertEquals(2, ThermalComfort.preferredFireDistance(1, 1, 1, false, false, true));
        assertTrue(ThermalComfort.preferredFireDistance(.8, .6, 1, false, true, false) > 2);
    }
}
