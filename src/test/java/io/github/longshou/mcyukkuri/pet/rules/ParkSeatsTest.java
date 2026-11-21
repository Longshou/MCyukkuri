package io.github.longshou.mcyukkuri.pet.rules;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParkSeatsTest {
    @Test void aBenchPairBesideAThreeByThreeFacilitySitsOnTheBand() {
        assertTrue(ParkSeats.watchable(3.0));
        assertTrue(ParkSeats.watchable(3.6));
        assertTrue(ParkSeats.watchable(ParkSeats.NEAR));
        assertTrue(ParkSeats.watchable(ParkSeats.FAR));
        assertFalse(ParkSeats.watchable(1.4));
        assertFalse(ParkSeats.watchable(9.1));
    }

    @Test void onlyTheNearestThreeSeatsAreKeptAndTheHashPicksTheFirst() {
        var seats = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("a", "b", "c"), ParkSeats.order(seats, 0));
        assertEquals(List.of("b", "c", "a"), ParkSeats.order(seats, 1));
        assertEquals(List.of("c", "a", "b"), ParkSeats.order(seats, 5));
        assertFalse(ParkSeats.order(seats, 4).contains("d"));
    }

    @Test void aNegativeHashStillPicksAKeptSeat() {
        var seats = List.of("a", "b", "c", "d");
        assertEquals(List.of("c", "a", "b"), ParkSeats.order(seats, -1));
        assertEquals(List.of("b", "c", "a"), ParkSeats.order(seats, -2));
        assertEquals(List.of("c", "a", "b"), ParkSeats.order(seats, Integer.MIN_VALUE + 1));
    }

    @Test void fewerSeatsThanTheKeepCountAndNoSeats() {
        assertEquals(List.of("b", "a"), ParkSeats.order(List.of("a", "b"), 1));
        assertEquals(List.of(), ParkSeats.order(List.of(), 7));
    }

    @Test void theBenchFacesTheSideTheFacilityIsOn() {
        assertTrue(ParkSeats.facesFirstNormal(2.5));
        assertFalse(ParkSeats.facesFirstNormal(-2.5));
        assertTrue(ParkSeats.facesFirstNormal(0));
    }
}
