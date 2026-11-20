package io.github.longshou.mcyukkuri.family;

import org.junit.jupiter.api.Test;

import static io.github.longshou.mcyukkuri.family.FarewellWindow.*;
import static org.junit.jupiter.api.Assertions.*;

class FarewellWindowTest {
    @Test void anApproachingChildCanComeIntoRange() {
        assertTrue(EXCHANGE.open(24 * 24));
        assertFalse(inRange(24 * 24));
        assertTrue(EXCHANGE.canAnswer(true, false));
    }

    @Test void aDepartureKeepsItsReplyThroughTheHandoffToOrdinaryTravel() {
        assertTrue(DEPARTURE.open(16 * 16));
        assertTrue(ONWARD.open(16 * 16));
        assertTrue(DEPARTURE.canAnswer(true, true));
        assertTrue(ONWARD.canAnswer(true, true));
        assertFalse(ONWARD.canAnswer(false, true));
        assertFalse(ONWARD.canAnswer(true, false));
    }

    @Test void aFinishedOrOutOfRangeDepartureExpires() {
        assertFalse(CLOSED.open(0));
        assertFalse(CLOSED.canAnswer(true, true));
        assertFalse(DEPARTURE.open(16 * 16 + .01));
        assertFalse(ONWARD.open(16 * 16 + .01));
    }
}
