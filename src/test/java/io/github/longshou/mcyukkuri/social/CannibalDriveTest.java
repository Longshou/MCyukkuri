package io.github.longshou.mcyukkuri.social;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CannibalDriveTest {
    @Test
    void hungerCanOutweighScornForTheSamePrey() {
        var harsh = new Temperament(55, 0, 20, 60, 0);
        // Fear of this opponent puts the hunt just below a harsh observer's escalation score.
        assertTrue(CannibalDrive.hunt(11, harsh, 8, 0, 0).willing());
        assertFalse(CannibalDrive.hunt(11, harsh, 8, 0, 60).willing());
        assertTrue(CannibalDrive.hunt(10, harsh, 8, 0, 60).willing());
        assertTrue(CannibalDrive.hunt(11, harsh, 6, 0, 60).willing());
    }

    @Test
    void equallyHungryHuntersCanGiveOppositeAnswers() {
        var willing = new Temperament(55, 10, 60, 60, 10);
        var reluctant = new Temperament(55, 95, 60, 60, 95);
        assertTrue(CannibalDrive.hunt(8, willing, 0, -2, 51).willing());
        assertFalse(CannibalDrive.hunt(8, reluctant, 0, -2, 0).willing());
    }
}
