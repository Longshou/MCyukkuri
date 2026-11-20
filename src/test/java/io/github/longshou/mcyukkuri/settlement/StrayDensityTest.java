package io.github.longshou.mcyukkuri.settlement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrayDensityTest {
    @Test void nightUsesOnlyTheSolarClockEvenDuringDaytimeThunder() {
        for (long day : new long[]{0, 24000, 48000}) {
            assertFalse(StrayDensity.night(day + 12999));
            assertTrue(StrayDensity.night(day + 13000));
            assertTrue(StrayDensity.night(day + 22999));
            assertFalse(StrayDensity.night(day + 23000));
            assertEquals(3, StrayDensity.target(8, StrayDensity.night(day + 6000), true));
            assertEquals(2, StrayDensity.target(8, StrayDensity.night(day + 18000), true));
        }
    }

    @Test void migrationReleasesResidenceInAnyDirectionOrDimension() {
        assertTrue(StrayDensity.stillResident(true, -96, 0));
        assertFalse(StrayDensity.stillResident(true, -96, 1));
        assertFalse(StrayDensity.stillResident(true, 70, 70));
        assertFalse(StrayDensity.stillResident(false, 0, 0));
    }

    @Test void bellsReuseTheirSurveyUntilExpiryAndEvictColdStreets() {
        var survey = new VillageSurvey.Cache<Integer, Integer>();
        assertEquals(9, survey.get(5, 100, () -> 9));
        assertEquals(9, survey.get(5, 6099, () -> fail("unexpected bed survey")));
        assertEquals(7, survey.get(5, 6100, () -> 7));
        for (int i = 6; i <= 261; i++) survey.get(i, 6101, () -> 4);
        assertEquals(8, survey.get(5, 6102, () -> 8));
        assertNull(survey.get(0, 6103, () -> null));
        assertNull(survey.get(0, 6104, () -> fail("empty street was rescanned")));
    }

    @Test void homesUpTheVillageSlopeStillFrontItsStreets() {
        // Seed 123456789: the only bed stands 38 blocks above its bell.
        assertTrue(StrayDensity.inFootprint(-336 - (-276), 165 - 158));
        assertTrue(StrayDensity.inFootprint(64, 0));
        assertFalse(StrayDensity.inFootprint(60, 30));
    }

    @Test void inhabitedBedsScaleArrivalsAndBadWeatherClosesVacancies() {
        for (int beds : new int[] {0, 1, 3, 4, 8, 16, 100}) {
            int fair = switch (beds) {
                case 0 -> 0;
                case 1, 3 -> 2;
                case 4 -> 3;
                case 8 -> 4;
                default -> 6;
            };
            assertEquals(fair, StrayDensity.target(beds, false, false));
            int floor = beds == 0 ? 0 : 1;
            assertEquals(Math.max(floor, fair - 1), StrayDensity.target(beds, true, false));
            assertEquals(Math.max(floor, fair - 1), StrayDensity.target(beds, false, true));
            assertEquals(Math.max(floor, fair - 2), StrayDensity.target(beds, true, true));
        }
    }

    @Test void conversionUsesTheExistingBodyAndBothCapsStillApply() {
        assertTrue(StrayDensity.admits(1, 7, 7, 8, 12, false));
        assertTrue(StrayDensity.admits(1, 8, 8, 8, 12, true));
        assertFalse(StrayDensity.admits(1, 8, 7, 8, 12, false));
        assertFalse(StrayDensity.admits(1, 6, 8, 8, 12, false));
        assertFalse(StrayDensity.admits(1, 5, 6, 8, 6, false));
        assertFalse(StrayDensity.admits(1, 9, 7, 8, 12, true));
        assertFalse(StrayDensity.admits(1, 7, 9, 8, 12, true));
        assertFalse(StrayDensity.admits(0, 1, 1, 8, 12, true));
        assertFalse(StrayDensity.admits(-1, 1, 1, 8, 12, false));
    }
}
