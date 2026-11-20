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
        assertTrue(DoorPassage.yields(false, true, false));
        assertFalse(DoorPassage.yields(true, false, true));
        for (boolean occupied : new boolean[]{false, true}) {
            assertTrue(DoorPassage.yields(occupied, occupied, true));
            assertFalse(DoorPassage.yields(occupied, occupied, false));
        }
    }

    @Test void kioskKeeperKeepsPriorityWhenHerCentrePassesTheJamb() {
        // TN-21 at tick 1778. Both centres have passed Z 704, but only the keeper still occupies the frame.
        boolean keeperInFrame = DoorPassage.overlaps(703.7254 - 703, 704.3254 - 703);
        boolean brotherInFrame = DoorPassage.overlaps(704.2366 - 703, 704.8366 - 703);
        assertFalse(DoorPassage.yields(keeperInFrame, brotherInFrame, true));
        assertTrue(DoorPassage.yields(brotherInFrame, keeperInFrame, false));
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

    @Test void aClearStopYieldsUntilTheOtherWalkersTrailingEdgeClears() {
        for (boolean positive : new boolean[]{false, true}) {
            assertTrue(DoorPassage.awaitingPassage(true, false, false));
            double partlyOut = positive ? 1.2 : -.2;
            assertTrue(DoorPassage.awaitingPassage(false, true,
                    DoorPassage.cleared(partlyOut - .3, partlyOut + .3, positive)));
            double fullyOut = positive ? 1.5 : -.5;
            assertFalse(DoorPassage.awaitingPassage(true, false,
                    DoorPassage.cleared(fullyOut - .3, fullyOut + .3, positive)));
            assertFalse(DoorPassage.awaitingPassage(false, false, false));
        }
    }
}
