package io.github.longshou.mcyukkuri.motor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import org.junit.jupiter.api.Test;

class MouthOccupancyTest {
    @Test
    void bothFieldLoadsYieldOnlyToEmergencyHunger() {
        for (var cargo : new Purpose[] {Purpose.HABITAT, Purpose.FORAGE}) {
            assertTrue(cargo.yieldsToMeal(14.99f));
            assertFalse(cargo.yieldsToMeal(15));
            assertFalse(cargo.yieldsToMeal(40));
        }
    }

    @Test
    void emergencyFieldCargoRuleLeavesOtherMouthUsesAlone() {
        for (var purpose : Purpose.values()) {
            if (purpose != Purpose.HABITAT && purpose != Purpose.FORAGE) {
                assertFalse(purpose.yieldsToMeal(0), purpose.name());
            }
        }
    }
}
