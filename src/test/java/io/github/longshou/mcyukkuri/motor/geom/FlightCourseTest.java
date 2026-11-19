package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightCourseTest {
    @Test void crossingTheThreatIsRejectedEvenWithADistantEndpoint() {
        var away = FlightCourse.awayUnit(2, 0, 0, 0, 0);
        assertEquals(Double.NEGATIVE_INFINITY, FlightCourse.scoreCandidate(2, 0, -8, 0, 0, 0, away));
        assertTrue(FlightCourse.passesNearThreat(2, 0, 2, 0, 0, 0, 3.8));
        assertTrue(FlightCourse.passesNearThreat(.2, 0, -4, 0, 0, 0, 3.8));
        assertFalse(FlightCourse.passesNearThreat(.2, 0, .2, 4, 0, 0, 3.8));
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

    @Test void dropLimitFollowsBodySizeAndCannotExceedTheDamageFreeFall() {
        double baby = FlightCourse.safeDrop(3, .10, 1);
        double adult = FlightCourse.safeDrop(3, 1.4, 1);
        assertEquals(64, FlightCourse.landingAfter(0, 64.5, 0, 1, 0, baby, (x,z) -> 64));
        assertEquals(64, FlightCourse.landingAfter(0, 65, 0, 1, 0, baby, (x,z) -> 64));
        assertEquals(Double.NEGATIVE_INFINITY, FlightCourse.landingAfter(0, 65.5, 0, 1, 0, baby, (x,z) -> 64));
        assertEquals(61, FlightCourse.landingAfter(0, 64, 0, 1, 0, adult, (x,z) -> 61));
        assertEquals(Double.NEGATIVE_INFINITY, FlightCourse.landingAfter(0, 64, 0, 1, 0, adult, (x,z) -> 60.99));
    }

    @Test void aSafeFarBankDoesNotHideAHoleOrFluidInTheMiddle() {
        for (double bottom : new double[]{-60, Double.NEGATIVE_INFINITY}) {
            assertEquals(Double.NEGATIVE_INFINITY, FlightCourse.landingAfter(0, 64, 0, 3, 0, 3,
                    (x,z) -> x >= 1 && x < 2 ? bottom : 64));
        }
        assertEquals(62, FlightCourse.landingAfter(.5, 64, 0, 2.5, 0, 1, (x,z) -> 64 - Math.floor(x)));
    }

    @Test void actualTurnOffTheHd04PadIsBlockedButTheStepAlongTheBrinkIsSafe() {
        DoubleBinaryOperator floor = (x,z) -> x < 33 ? 64 : -60;
        assertEquals(Double.NEGATIVE_INFINITY, FlightCourse.landingAfter(32.95450, 64, 525.18513, 33.09297, 525.14621, 3, floor));
        assertEquals(64, FlightCourse.landingAfter(32.95450, 64, 525.18513, 32.95450, 525.14621, 3, floor));
    }
}
