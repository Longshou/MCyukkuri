package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightCourseTest {
    @Test void crossingTheThreatIsRejectedEvenWithADistantEndpoint() {
        var away = FlightCourse.awayUnit(2, 0, 0, 0, 0);
        assertEquals(Double.NEGATIVE_INFINITY, FlightCourse.scoreCandidate(2, 0, -8, 0, 0, 0, away));
        assertTrue(FlightCourse.passesNearThreat(2, 0, 2, 0, 0, 0, 3.8));
        assertTrue(FlightCourse.scoreCandidate(2, 0, 10, 0, 0, 0, away)
                > FlightCourse.scoreCandidate(2, 0, 6, 6, 0, 0, away));
    }

    @Test void commitUntilArrivalStallFlankOrTwoSeconds() {
        var dir = FlightCourse.awayUnit(0, 0, 0, 0, 0);
        assertFalse(FlightCourse.shouldReplan(39, false, false, dir, dir.rotate(50)));
        assertTrue(FlightCourse.shouldReplan(40, false, false, dir, dir));
        assertTrue(FlightCourse.shouldReplan(1, false, false, dir, dir.rotate(80)));
        assertTrue(FlightCourse.shouldReplan(1, true, false, dir, dir));
        assertTrue(FlightCourse.shouldReplan(1, false, true, dir, dir));
    }
}
