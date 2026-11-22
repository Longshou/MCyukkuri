package io.github.longshou.mcyukkuri.motor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import org.junit.jupiter.api.Test;

class MouthOccupancyTest {
    @Test
    void fieldLoadsYieldToHungerPerPurpose() {
        assertTrue(Purpose.HABITAT.yieldsToMeal(29.99f));
        assertFalse(Purpose.HABITAT.yieldsToMeal(30));
        assertTrue(Purpose.FORAGE.yieldsToMeal(14.99f));
        assertFalse(Purpose.FORAGE.yieldsToMeal(15));
        assertFalse(Purpose.FORAGE.yieldsToMeal(40));
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
