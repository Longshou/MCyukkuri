package io.github.longshou.mcyukkuri.play;

import static org.junit.jupiter.api.Assertions.*;

import io.github.longshou.mcyukkuri.pet.rules.HouseRule;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;
import java.util.List;
import org.junit.jupiter.api.Test;

class JuiceTubTest {
    private static final JuiceTub.Layout LAYOUT = JuiceTub.layout();

    private static List<String> ids(double width, int level) {
        return JuiceTub.seatsFor(LAYOUT, width, level).stream().map(JuiceTub.Seat::id).toList();
    }

    @Test void oneItemIsOneLevelAndAFullTubTakesNothing() {
        int level = 0;
        for (int i = 1; i <= JuiceTub.CAPACITY; i++) { level = JuiceTub.filled(level); assertEquals(i, level); }
        assertTrue(JuiceTub.full(level));
        assertEquals(JuiceTub.CAPACITY, JuiceTub.filled(level));
        assertFalse(JuiceTub.full(JuiceTub.CAPACITY - 1));
    }

    @Test void aSoakUsesOneLevelAndNeverGoesBelowEmpty() {
        assertEquals(3, JuiceTub.drained(4));
        assertEquals(0, JuiceTub.drained(1));
        assertEquals(0, JuiceTub.drained(0));
    }

    @Test void aHurtSoakPaysOutSlowlyNotAllAtOnce() {
        float health = 0;
        int wounds = 0, washes = 0;
        for (int step = 1; step <= JuiceTub.STEPS; step++) {
            var payout = JuiceTub.payout(true, step);
            assertTrue(payout.heal());
            health += JuiceTub.healthStep(20);
            if (payout.mend()) wounds++;
            if (JuiceTub.washesMarks(step)) washes++;
        }
        assertEquals(4.8f, health, 1e-4);          // under a quarter of a 20-health body per soak
        assertEquals(3, wounds);                    // three grades per soak: a deep wound takes more than one soak
        assertEquals(2, washes);
        assertTrue(JuiceTub.SOAK_TICKS <= 300);
    }

    @Test void aHealthySoakOnlySettlesAndCleans() {
        for (int step = 1; step <= JuiceTub.STEPS; step++) {
            var payout = JuiceTub.payout(false, step);
            assertFalse(payout.heal());
            assertFalse(payout.mend());
        }
        // A whole soak lifts about one 0-100 stage band, a little more for a timid body.
        float bold = 0, timid = 0;
        for (int step = 1; step <= JuiceTub.STEPS; step++) {
            bold += JuiceTub.uneaseStep(0);
            timid += JuiceTub.uneaseStep(1);
        }
        assertEquals(18f, bold, 1e-4);
        assertEquals(24f, timid, 1e-4);            // still short of one 25-wide band
    }

    @Test void eachBodyCarriesTheBanThatFitsItsState() {
        assertEquals(HouseRule.NO_TUB_WHEN_HEALTHY, JuiceTub.tubRule(false));
        assertEquals(HouseRule.NO_TUB_WHEN_HURT, JuiceTub.tubRule(true));
        // A wild or stray body has no master, so the unattached answer lets it in; holding or waiting keeps it out.
        assertTrue(JuiceTub.permitsSoak(Choice.ALLOW));
        assertTrue(JuiceTub.permitsSoak(Choice.BREAK));
        assertFalse(JuiceTub.permitsSoak(Choice.HOLD));
        assertFalse(JuiceTub.permitsSoak(Choice.WAIT));
        // Both are the owner's choice: a household starts with only the sukkiri ban.
        assertFalse(HouseRule.DEFAULT.contains(HouseRule.NO_TUB_WHEN_HEALTHY));
        assertFalse(HouseRule.DEFAULT.contains(HouseRule.NO_TUB_WHEN_HURT));
        assertTrue(HouseRule.DEFAULT.contains(HouseRule.NO_SUKKIRI));
    }

    @Test void babiesNeedTheBenchAndItNeedsEnoughJuice() {
        assertEquals(List.of(), ids(.30, 1));
        assertEquals(List.of(), ids(.30, 2));
        assertEquals(List.of("small_bench_left", "small_bench_middle", "small_bench_right"), ids(.30, 3));
        assertEquals(List.of("small_bench_left", "small_bench_middle", "small_bench_right"), ids(.30, 4));
    }

    @Test void aGrownBodyTakesTheFloorSeatsItFits() {
        assertEquals(List.of("adult_left", "adult_right", "adult_alone"), ids(.50, 1));
        assertEquals(List.of("adult_alone"), ids(.75, 1));
        assertEquals(List.of("adult_alone"), ids(.84, 4));
        assertEquals(List.of(), ids(.85, 4));
        assertTrue(JuiceTub.tooBig(.85));
        assertFalse(JuiceTub.tooBig(.84));
    }

    @Test void anEmptyTubSeatsNobody() {
        for (double width : new double[]{.30, .50, .75}) assertEquals(List.of(), ids(width, 0));
    }

    @Test void theRouteGoesOverTheStoolAndRimToTheSeatAndBackTheSameWay() {
        var seat = JuiceTub.seatsFor(LAYOUT, .75, 2).getFirst();
        var route = JuiceTub.route(LAYOUT, seat);
        var points = route.points();
        assertEquals(9, points.size());
        var at = points.get(route.seat());
        assertEquals(seat.x() / 16, at[0], 1e-9);
        assertEquals(seat.y() / 16, at[1], 1e-9);
        assertEquals(seat.z() / 16, at[2], 1e-9);
        for (int i = 0; i < route.seat(); i++) assertArrayEquals(points.get(i), points.get(points.size() - 1 - i));
        // Never a step above what a body climbs: stool, then rim, then down onto the bench.
        for (int i = 1; i < points.size(); i++) assertTrue(Math.abs(points.get(i)[1] - points.get(i - 1)[1]) <= .45);
        assertEquals(LAYOUT.rimTop() / 16, points.get(2)[1], 1e-9);
    }
}
