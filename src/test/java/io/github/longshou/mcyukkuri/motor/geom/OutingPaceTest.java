package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutingPaceTest {
    @Test void attitudeIntervalStaysWithinHumanStrollBand() {
        double caringLow = OutingPace.baseRequest(1, -1);
        double caringHigh = OutingPace.baseRequest(1, 1);
        double neutral = OutingPace.baseRequest(2, 0);
        double cruel = OutingPace.baseRequest(0, 0);
        assertTrue(caringLow >= .75 && caringLow < caringHigh);
        assertTrue(caringHigh < neutral && neutral < cruel && cruel <= .90);
    }

    @Test void slowerCompanionLowersCruiseAndFloor() {
        double base = OutingPace.baseRequest(1, 0);
        double adultCruise = OutingPace.cruiseRequest(base, OutingPace.REF_WALK);
        double tiredReimuCruise = OutingPace.cruiseRequest(base, .085);
        double frailCruise = OutingPace.cruiseRequest(base, .051);
        double babyCruise = OutingPace.cruiseRequest(base, .059);

        assertTrue(adultCruise > tiredReimuCruise && tiredReimuCruise > babyCruise && babyCruise > frailCruise);
        assertTrue(OutingPace.groundSpeedPerSecond(.26, adultCruise) > 1.7);
        assertTrue(OutingPace.groundSpeedPerSecond(.26, frailCruise) < 1.1);
        assertTrue(OutingPace.lagFloor(adultCruise, OutingPace.REF_WALK) >= .54);
        assertTrue(OutingPace.lagFloor(frailCruise, .051) < OutingPace.lagFloor(adultCruise, OutingPace.REF_WALK));
    }

    @Test void smoothSlowThenWaitBeforeLeashGoesTaut() {
        double base = OutingPace.baseRequest(1, 0);
        // Shy pet (outerDistance = 2.2) at 2.0 -> CRUISE.
        var cruise = OutingPace.evaluate(base, List.of(new OutingPace.Companion(2.0, 2.2, .095, true)), false);
        assertEquals(OutingPace.Mode.CRUISE, cruise.mode());
        assertEquals(-1, cruise.laggingIndex());

        // Slightly behind (2.8) -> SLOW between floor and cruise, not a binary cliff.
        var slow = OutingPace.evaluate(base, List.of(new OutingPace.Companion(2.8, 2.2, .095, true)), false);
        assertEquals(OutingPace.Mode.SLOW, slow.mode());
        assertEquals(0, slow.laggingIndex());
        assertTrue(slow.target() < slow.cruise() && slow.target() > slow.floor());

        // Bold pet (outerDistance = 5.8) on lead: comfort capped at 4.6 and wait at 5.1 (< 6.0 elastic drag).
        var boldWait = OutingPace.evaluate(base, List.of(new OutingPace.Companion(5.15, 5.8, .10, true)), false);
        assertEquals(OutingPace.Mode.WAIT, boldWait.mode());
        assertEquals(0.0, boldWait.target());
        assertEquals("taut_wait", boldWait.cause());
        assertTrue(OutingPace.waitDistance(OutingPace.followComfort(5.8, true), true) < OutingPace.LEASH_ELASTIC_DIST);

        // Wait hysteresis: still waiting at 4.8 (> 5.1 - 0.5), released at 4.5.
        var stillWait = OutingPace.evaluate(base, List.of(new OutingPace.Companion(4.8, 5.8, .10, true)), true);
        assertEquals(OutingPace.Mode.WAIT, stillWait.mode());
        var released = OutingPace.evaluate(base, List.of(new OutingPace.Companion(4.5, 5.8, .10, true)), true);
        assertEquals(OutingPace.Mode.CRUISE, released.mode());
    }

    @Test void stepHaltsImmediatelyOnWaitAndResumesFromFloor() {
        double base = OutingPace.baseRequest(1, 0);
        var wait = OutingPace.evaluate(base, List.of(new OutingPace.Companion(5.2, 5.8, .10, true)), false);
        assertEquals(0.0, OutingPace.step(.80, wait));

        var cruise = OutingPace.evaluate(base, List.of(new OutingPace.Companion(1.8, 2.2, .10, true)), false);
        double resumed = OutingPace.step(0.0, cruise);
        assertEquals(cruise.floor(), resumed, 1e-6);
        assertTrue(OutingPace.step(resumed, cruise) > resumed);
    }
}
