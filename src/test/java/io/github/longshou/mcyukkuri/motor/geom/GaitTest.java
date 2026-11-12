package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GaitTest {
    private static double groundPace(double attribute, double request, double factor) {
        double control = Gait.groundControl(attribute * request, factor);
        return .98 * control * control / (1 - .60 * Ballistic.AIR_DRAG);
    }

    @Test void strongestGroundRequestsStayBelowAPlayerAndRetainPaceOrder() {
        double adult = Gait.GROUND_ATTRIBUTE;
        double ordinary = groundPace(adult, 1, 1);
        double young = groundPace(adult * Math.sqrt(.55), 1, 1);
        double loaded = groundPace(adult, 1, .7);
        double panic = groundPace(adult * 1.3, 1.2, 1);
        double urgentChen = groundPace(adult * 1.2 * 1.3, 1.8, Gait.temperament(100));
        assertTrue(young < ordinary && loaded < ordinary && ordinary < panic && panic < urgentChen);
        assertTrue(ordinary < Gait.HUMAN_WALK / 2 && urgentChen < Gait.HUMAN_WALK);
    }

    @Test void PredatorChaseOutpacesGroundFlightWithoutAnOverspeedDive() {
        double prey = groundPace(Gait.GROUND_ATTRIBUTE * 1.2 * 1.3, 1.2, Gait.temperament(100));
        double chase = Gait.flight(Gait.FLIGHT_ATTRIBUTE, 1.2, 1, Gait.temperament(0));
        double dive = Gait.flight(Gait.FLIGHT_ATTRIBUTE, 1.6, 1, Gait.temperament(100));
        double young = Gait.flight(Gait.FLIGHT_ATTRIBUTE, 1.2, .55, Gait.temperament(0));
        assertTrue(prey < chase && young < chase && chase < dive && dive < Gait.HUMAN_WALK);
    }

    @Test void poweredSwimAndShortStepsHaveHeadroomEvenWithLargeDataFactors() {
        assertTrue(Gait.swim(.045, 1) > Gait.swim(.03, 1));
        assertTrue(Gait.swim(10, 1) < Gait.walk(10, 1));
        assertTrue(Gait.walk(10, 1) < Gait.HUMAN_WALK);
        assertTrue(Gait.hop(.45, 1) < Gait.hop(1, 1));
        assertTrue(Gait.hop(1.5, Gait.temperament(100)) < Gait.HUMAN_WALK);
    }
}
