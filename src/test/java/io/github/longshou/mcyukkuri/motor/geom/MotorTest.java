package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Reason;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MotorTest {
    @Test void cancellationCannotEraseACompletedBreak() {
        var broken = new Outcome.Done("pane_broken hits=3");
        assertSame(broken, broken.afterCancellation("home_claim_stopped"));
        var stopped = new Outcome.Interrupted("owner_inactive");
        assertSame(stopped, stopped.afterCancellation("home_claim_stopped"));
        assertInstanceOf(Outcome.Interrupted.class,
                Outcome.of(Reason.NO_CONTACT, 1, "stroke_obstructed").afterCancellation("home_claim_stopped"));
    }
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
