package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.motor.Motor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MotorTest {
    @Test void takeoffClearsGroundBeforeApplyingExactlyOneImpulse() {
        boolean[] grounded = {true};
        int[] impulses = {0};
        Motor.Takeoff.apply(value -> grounded[0] = value, () -> {
            assertFalse(grounded[0], "first movement must select air friction");
            impulses[0]++;
        });
        assertFalse(grounded[0]);
        assertEquals(1, impulses[0]);
    }
}
