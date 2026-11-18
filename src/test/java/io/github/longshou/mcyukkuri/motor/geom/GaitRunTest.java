package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GaitRunTest {
    @Test void jumpingRunsOnFastOrExplicitRequestsOnly() {
        assertTrue(Gait.isRunning(false, true, 1.2, false));
        assertTrue(Gait.isRunning(false, true, .8, true));
        assertFalse(Gait.isRunning(false, true, 1, false));
        assertFalse(Gait.isRunning(true, true, 1.2, true));
        assertFalse(Gait.isRunning(false, false, 1.2, true));
    }
}
