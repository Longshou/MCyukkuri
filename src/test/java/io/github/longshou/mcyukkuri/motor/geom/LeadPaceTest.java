package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeadPaceTest {
    @Test void hopRequestIsAFastRequestAndWalkIsNot() {
        assertTrue(Gait.fast(LeadPace.HOP_REQUEST));
        assertFalse(Gait.fast(LeadPace.WALK_REQUEST));
        assertEquals(LeadPace.HOP_REQUEST, LeadPace.request(true));
        assertEquals(LeadPace.WALK_REQUEST, LeadPace.request(false));
    }

    @Test void comfortShiftsByIndividualAndStaysPastTheFarthestFollowStop() {
        double clingyTimidSmall = LeadPace.comfort(0, 100, 0);
        double bold = LeadPace.comfort(1, 0, 100);
        double plain = LeadPace.comfort(.5, 50, 50);
        assertTrue(clingyTimidSmall < plain && plain < bold);
        for (int a = 0; a <= 100; a += 20) for (int b = 0; b <= 100; b += 20) for (double s = 0; s <= 1; s += .25) {
            double comfort = LeadPace.comfort(s, a, b);
            assertTrue(comfort >= 4.2 && comfort <= 4.5);
            assertTrue(comfort > LeadPace.CAUGHT_UP, "a body that settled at its follow stop is never trailing again");
        }
    }

    @Test void tautPointFollowsTheElasticAndNeverPrecedesTheHurry() {
        double start = LeadPace.elasticStart(.8, .6);
        assertEquals(4.6, start, 1e-9);
        for (int b = 0; b <= 100; b += 25) {
            double comfort = LeadPace.comfort(1, 50, b);
            double taut = LeadPace.tautAt(b, comfort, start);
            assertTrue(taut >= comfort + .3 && taut < LeadPace.ELASTIC);
        }
        assertTrue(LeadPace.tautAt(0, 4.2, start) <= LeadPace.tautAt(100, 4.2, start), "a timid one feels it sooner");
        assertTrue(LeadPace.tautAt(-50, 4.2, start) == LeadPace.tautAt(0, 4.2, start) && LeadPace.tautAt(500, 4.2, start) == LeadPace.tautAt(100, 4.2, start));
    }

    @Test void trailingHasHysteresis() {
        double comfort = 4.3;
        assertFalse(LeadPace.trailing(false, comfort, comfort));
        assertTrue(LeadPace.trailing(false, comfort + .1, comfort));
        assertTrue(LeadPace.trailing(true, comfort - .1, comfort), "a hopping body goes on hopping inside its comfort");
        assertTrue(LeadPace.trailing(true, LeadPace.CAUGHT_UP + .05, comfort));
        assertFalse(LeadPace.trailing(true, LeadPace.CAUGHT_UP, comfort));
    }

    @Test void boutsGetWeakerAndRarerAsTheReserveFalls() {
        double start = LeadPace.elasticStart(.8, .6);
        assertTrue(LeadPace.boutGap(0, .6) > LeadPace.boutGap(1, .6));
        assertTrue(LeadPace.boutTicks(0) < LeadPace.boutTicks(1));
        assertTrue(LeadPace.boutReach(0, start) < LeadPace.boutReach(1, start));
        assertTrue(LeadPace.boutReach(1, start) < start - .3, "a bout never strains the lead into the elastic");
        assertTrue(LeadPace.boutReach(0, start) > LeadPace.CAUGHT_UP - .5, "a bout pulls out past where a follow settles");
        assertTrue(LeadPace.boutGap(.5, .3) > LeadPace.boutGap(.5, .9), "a stronger will pulls again sooner");
        assertTrue(LeadPace.resist(0) > LeadPace.resist(100));
        assertEquals(0, LeadPace.reserve(25, 25));
        assertEquals(0, LeadPace.reserve(10, 25));
        assertEquals(1, LeadPace.reserve(100, 25));
    }

    @Test void everyBoutCostsEnergySoTheyEnd() {
        for (double resist = .2; resist <= 1; resist += .2) {
            float cost = LeadPace.boutCost(resist);
            assertTrue(cost >= 4 && cost <= 10);
            double energy = 100;
            int bouts = 0;
            while (energy >= 25 && bouts < 100) { energy -= cost; bouts++; }
            assertTrue(bouts < 20, "a full-energy body is spent within a bounded number of bouts");
        }
    }

    @Test void aBodyThatStandsStillHasSettledWhereverVanillaStopsIt() {
        double start = LeadPace.elasticStart(.8, .6);
        assertFalse(LeadPace.settled(LeadPace.SETTLE_TICKS - 1, 3.0, start));
        assertTrue(LeadPace.settled(LeadPace.SETTLE_TICKS, LeadPace.CAUGHT_UP + .3, start));
        assertFalse(LeadPace.settled(1000, start, start), "inside the elastic only: a body held at the taut line is still trailing");
        assertFalse(LeadPace.settled(0, 2.4, start));
    }
}
