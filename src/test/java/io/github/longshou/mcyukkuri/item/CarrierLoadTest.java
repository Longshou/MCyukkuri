package io.github.longshou.mcyukkuri.item;

import static org.junit.jupiter.api.Assertions.*;

import io.github.longshou.mcyukkuri.item.CarrierLoad.Grade;
import org.junit.jupiter.api.Test;

class CarrierLoadTest {
    @Test void gradesHoldUpToThreeAdults() {
        assertEquals(4, Grade.PLAIN.capacity());
        assertEquals(8, Grade.FINE.capacity());
        assertEquals(12, Grade.LARGE.capacity());
        assertEquals(3 * CarrierLoad.stageLoad("adult"), Grade.LARGE.capacity());
    }

    @Test void aStageLoadsWhatItsBodyTakes() {
        assertEquals(4, CarrierLoad.stageLoad("adult"));
        assertEquals(2, CarrierLoad.stageLoad("subadult"));
        assertEquals(2, CarrierLoad.stageLoad("juvenile"));
        assertEquals(1, CarrierLoad.stageLoad("baby"));
    }

    @Test void aBasketTakesWhatStillFits() {
        assertTrue(CarrierLoad.fits(Grade.PLAIN, 0, 4));
        assertTrue(CarrierLoad.fits(Grade.PLAIN, 3, 1));
        assertTrue(CarrierLoad.fits(Grade.FINE, 4, 4));
        assertFalse(CarrierLoad.fits(Grade.FINE, 4, 5));
        assertFalse(CarrierLoad.fits(Grade.PLAIN, 3, 2));
        assertTrue(CarrierLoad.fits(Grade.LARGE, 8, 4));
    }

    @Test void resistanceRisesWithInsistenceAndEsteemAndFallsWithFear() {
        assertTrue(CarrierLoad.resistance(1, 100, 0) > CarrierLoad.resistance(0, 0, 100));
        assertEquals(0.4, CarrierLoad.resistance(0, 0, 0), 1e-9);
    }

    @Test void weaknessMustPassResistance() {
        double resistance = CarrierLoad.resistance(0, 0, 0);
        assertTrue(CarrierLoad.overpowered(0.5f, resistance));
        assertFalse(CarrierLoad.overpowered(0.3f, resistance));
        assertFalse(CarrierLoad.overpowered(0.5f, 0.5));
    }
}
