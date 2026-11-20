package io.github.longshou.mcyukkuri.settlement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StraySightTest {
    @Test void openRoadBeyondTheOldExclusionRadiusIsVisible() {
        // Grass underneath and a non-opaque flower on the path leave the whole sightline open.
        assertFalse(StraySight.hidden((x,y,z) -> y < 0, .5, 1.62, .5, 38.32, .45, .5, .45, .45));
    }

    @Test void completeWallHidesTheAdultButAGapOrSecondObserverVetoesTheSite() {
        StraySight.Terrain wall = (x,y,z) -> x == -4;
        assertTrue(StraySight.hidden(wall, -10.5, 1.62, 0, .5, .6, 0, .55, .6));
        assertFalse(StraySight.hidden((x,y,z) -> x == -4 && z >= 0,
                -10.5, 1.62, 0, .5, .6, 0, .55, .6));
        assertFalse(StraySight.hidden(wall, 8, 1.62, 0, .5, .6, 0, .55, .6));
    }

    @Test void aChildBesideAHiddenParentNeedsItsOwnCover() {
        StraySight.Terrain endOfWall = (x,y,z) -> x == 8 && z == 0;
        assertTrue(StraySight.hidden(endOfWall, .5, 1.62, .5, 12.5, .6, .5, .4, .6));
        assertFalse(StraySight.hidden(endOfWall, .5, 1.62, .5, 12.5, .3, 1.5, .2, .3));
    }
}
