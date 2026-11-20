package io.github.longshou.mcyukkuri.settlement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StraySightTest {
    @Test void openRoadBeyondTheOldExclusionRadiusIsVisible() {
        // Grass underneath and a non-opaque flower on the path leave the whole sightline open.
        assertFalse(StraySight.hidden((x,y,z) -> y < 0, 128, .5, 1.62, .5, 38.32, .45, .5, .45, .45));
    }

    @Test void completeWallHidesTheAdultButAGapOrSecondObserverVetoesTheSite() {
        StraySight.Terrain wall = (x,y,z) -> x == -4;
        assertTrue(StraySight.hidden(wall, 128, -10.5, 1.62, 0, .5, .6, 0, .55, .6));
        assertFalse(StraySight.hidden((x,y,z) -> x == -4 && z >= 0, 128,
                -10.5, 1.62, 0, .5, .6, 0, .55, .6));
        assertFalse(StraySight.hidden(wall, 128, 8, 1.62, 0, .5, .6, 0, .55, .6));
    }

    @Test void aChildBesideAHiddenParentNeedsItsOwnCover() {
        StraySight.Terrain endOfWall = (x,y,z) -> x == 8 && z == 0;
        assertTrue(StraySight.hidden(endOfWall, 128, .5, 1.62, .5, 12.5, .6, .5, .4, .6));
        assertFalse(StraySight.hidden(endOfWall, 128, .5, 1.62, .5, 12.5, .3, 1.5, .2, .3));
    }

    @Test void onlyObserversWithinHorizontalTrackingRangeCanVetoAnArrival() {
        StraySight.Terrain unqueried = (x,y,z) -> fail("out-of-range player queried terrain");
        int entityRange = StraySight.trackingRange(128, 10, 12);
        int clientRange = StraySight.trackingRange(128, 10, 2);
        assertEquals(128, entityRange);
        assertEquals(32, clientRange);
        assertEquals(48, StraySight.trackingRange(256, 3, 12));
        assertTrue(StraySight.hidden(unqueried, entityRange, 4096, 64, 0, 0, 64, 0, .4, .4));
        assertTrue(StraySight.hidden(unqueried, clientRange, 32, 64, 1, 0, 64, 0, .4, .4));
        StraySight.Terrain open = (x,y,z) -> false;
        assertFalse(StraySight.hidden(open, clientRange, 32, 100, 0, 0, 64, 0, .4, .4));
        assertFalse(StraySight.hidden(open, entityRange, 32, 64, 1, 0, 64, 0, .4, .4));
    }
}
