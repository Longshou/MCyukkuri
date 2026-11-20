package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import io.github.longshou.mcyukkuri.predator.PredatorFlight.NestSurvey;
import io.github.longshou.mcyukkuri.predator.PredatorFlight.Task;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredatorFlightTest {
    @Test void claimingAHomeChecksItsPadBeforeContinuingToPerchSelection() {
        assertEquals(Task.CLEAR_PAD, NestSurvey.CLAIMED_HOME.chooseWork(() -> true));
        assertEquals(Task.NONE, NestSurvey.CLAIMED_HOME.chooseWork(() -> false));
    }

    @Test void surveyingWithoutAClaimDoesNotCheckThePadAgain() {
        assertEquals(Task.FOUND, NestSurvey.FOUNDING_SITE.chooseWork(() -> fail("no home claimed")));
        assertEquals(Task.NONE, NestSurvey.UNAVAILABLE.chooseWork(() -> fail("no home claimed")));
    }

    @Test void familyFlightsLandOnTheSameReserveAsPatrol() {
        for (var task : new PredatorFlight.Task[]{PredatorFlight.Task.DELIVER,PredatorFlight.Task.VISIT_YOUNG,
                PredatorFlight.Task.BROOD_FLIGHT,PredatorFlight.Task.PATROL}) {
            assertFalse(task.needsLanding(15));
            assertTrue(task.needsLanding(14.99));
        }
        assertFalse(PredatorFlight.Task.LAND.needsLanding(10));
    }
    @Test void anEarlyFlightRefusalBlocksTheSameVisitUntilItsDeadline() {
        var refused = new DeadlineMap<PredatorFlight.Visit>();
        var child = new UUID(0, 1);
        var firstStand = new PredatorFlight.Visit(child, 481, 482);
        var secondStand = new PredatorFlight.Visit(child, 481, 483);
        long retryAt = PredatorFlight.visitRetryAt(1061, 61);
        assertEquals(1801, retryAt);
        refused.put(firstStand, retryAt);
        assertTrue(refused.active(firstStand, 1062));
        assertTrue(refused.active(firstStand, 1800));
        assertFalse(refused.active(firstStand, 1801));
        assertFalse(refused.active(secondStand, 1062));
        refused.put(secondStand, PredatorFlight.visitRetryAt(1122, 61));
        assertTrue(refused.active(firstStand, 1123));
        assertTrue(refused.active(secondStand, 1123));
    }

    @Test void aTimedOutGroundVisitPausesOnlyItsOwnCondition() {
        assertFalse(PredatorFlight.Task.VISIT_YOUNG.timedOut(800));
        assertTrue(PredatorFlight.Task.VISIT_YOUNG.timedOut(801));
        var refused = new DeadlineMap<PredatorFlight.Visit>();
        var visit = new PredatorFlight.Visit(new UUID(0, 1), 481, 482);
        long retryAt = PredatorFlight.visitRetryAt(1801, 801);
        assertEquals(2601, retryAt);
        refused.put(visit, retryAt);
        assertTrue(refused.active(visit, 2600));
        assertFalse(refused.active(new PredatorFlight.Visit(new UUID(0, 2), 481, 482), 1802));
        assertFalse(refused.active(new PredatorFlight.Visit(visit.child(), 480, 482), 1802));
        assertFalse(refused.active(visit, 2601));
    }
}
