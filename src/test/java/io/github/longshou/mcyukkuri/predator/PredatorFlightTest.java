package io.github.longshou.mcyukkuri.predator;

import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredatorFlightTest {
    @Test void familyFlightsLandOnTheSameReserveAsPatrol() {
        for (var task : new PredatorFlight.Task[]{PredatorFlight.Task.DELIVER,PredatorFlight.Task.VISIT_YOUNG,
                PredatorFlight.Task.BROOD_FLIGHT,PredatorFlight.Task.PATROL}) {
            assertFalse(task.needsLanding(15));
            assertTrue(task.needsLanding(14.99));
        }
        assertFalse(PredatorFlight.Task.LAND.needsLanding(10));
    }
    @Test void aTimedOutGroundVisitNeedsAChangedChildOrLandingCell() {
        assertFalse(PredatorFlight.Task.VISIT_YOUNG.timedOut(800));
        assertTrue(PredatorFlight.Task.VISIT_YOUNG.timedOut(801));
        var refused=new HashMap<UUID,Long>();
        var a=new PredatorFlight.Visit(new UUID(0,1),481);
        var sibling=new PredatorFlight.Visit(new UUID(0,2),481);
        assertTrue(a.available(refused));
        a.timedOut(refused);
        assertFalse(a.available(refused));
        assertTrue(sibling.available(refused));
        sibling.timedOut(refused);
        assertFalse(a.available(refused));
        assertTrue(new PredatorFlight.Visit(a.child(),482).available(refused));
    }
}
