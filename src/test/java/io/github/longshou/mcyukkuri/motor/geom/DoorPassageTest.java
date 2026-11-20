package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DoorPassageTest {
    @Test void keeperLeavesAFreeFollowerCellOnEitherSide() {
        double keeperRadius = .30, petRadius = .375;
        for (boolean positive : new boolean[]{false, true}) {
            double keeper = DoorPassage.stand(positive, true);
            double follower = DoorPassage.stand(positive, false);
            assertTrue(Math.abs(keeper - follower) > keeperRadius + petRadius);
            assertTrue(DoorPassage.cleared(follower - petRadius, follower + petRadius, positive));
        }
        assertFalse(DoorPassage.cleared(.95, 1.7, true));
        assertFalse(DoorPassage.cleared(-.6, .15, false));
    }

    @Test void doorwayOccupantHasPriorityEvenAgainstStableOrdering() {
        assertTrue(DoorPassage.yields(-.1, .5, false));
        assertFalse(DoorPassage.yields(.5, -.1, true));
        assertTrue(DoorPassage.yields(1.1, .5, false));
        assertTrue(DoorPassage.yields(-.1, 1.1, true));
        assertFalse(DoorPassage.yields(1.1, -.1, false));
    }

    @Test void keeperResumesWhenPetsClearOrTurnsBackAtTheLimit() {
        assertEquals(DoorPassage.Escort.WAIT, DoorPassage.escort(true, 199, 200));
        assertEquals(DoorPassage.Escort.CLEARED, DoorPassage.escort(false, 199, 200));
        assertEquals(DoorPassage.Escort.LAGGING, DoorPassage.escort(true, 200, 200));
        assertEquals(DoorPassage.Escort.CLEARED, DoorPassage.escort(false, 200, 200));
    }

    @Test void clearedFrameClosesImmediatelyOrAtTheDeadline() {
        assertFalse(DoorPassage.close(false, true, 19, 20));
        assertTrue(DoorPassage.close(false, false, 19, 20));
        assertTrue(DoorPassage.close(false, true, 20, 20));
        assertFalse(DoorPassage.close(true, false, 40, 20));
        assertTrue(DoorPassage.close(false, true, 41, 20));
    }

    @Test void stoppedKioskResidentStillOverlapsTheDoorWithHisTrailingEdge() {
        double centre = 702.767 - 703;
        assertTrue(DoorPassage.overlaps(centre - .3, centre + .3));
        assertFalse(DoorPassage.stopSide(null, -1.5, centre));
        assertFalse(DoorPassage.stopSide(false, 2.5, centre));
        double exit = DoorPassage.stand(false, false);
        assertFalse(DoorPassage.overlaps(exit - .3, exit + .3));
    }

    @Test void stopClearsInTravelDirectionOrBacksOutWhenBlocked() {
        assertEquals(1.5, DoorPassage.stopStand(true, side -> DoorPassage.stand(side, false)));
        assertEquals(-.5, DoorPassage.stopStand(true, side -> side ? null : DoorPassage.stand(false, false)));
        assertNull(DoorPassage.stopStand(true, side -> null));
    }

    @Test void crowdedExitHasRoomToClearBeforeTheNextCellCentre() {
        double radius = .3, tolerance = .0005;
        for (boolean positive : new boolean[]{false, true}) {
            double neighbor = positive ? 2 : -1;
            assertTrue(Math.abs(neighbor - DoorPassage.stand(positive, false)) < 2 * radius);
            double edge = DoorPassage.stopEdge(positive, radius, tolerance);
            assertTrue(Math.abs(neighbor - edge) > 2 * radius);
            assertTrue(DoorPassage.cleared(edge - radius, edge + radius, positive));
        }
    }
}
