package io.github.longshou.mcyukkuri.family;

import org.junit.jupiter.api.Test;

import static io.github.longshou.mcyukkuri.family.FarewellWindow.*;
import static org.junit.jupiter.api.Assertions.*;

class FarewellWindowTest {
    @Test void anApproachingChildCanComeIntoRange() {
        assertEquals(EXCHANGE, EXCHANGE.observed(24 * 24, false, false));
        assertFalse(inRange(24 * 24));
    }

    @Test void theParentStopsItsErrandAtTheFirstVisibleWord() {
        assertFalse(EXCHANGE.canListen(-1, true));
        assertFalse(EXCHANGE.canListen(1, false));
        assertTrue(EXCHANGE.canListen(1, true));
        assertFalse(HANDOFF.canListen(-2, true));
        assertTrue(ONWARD.canListen(-2, true));
    }

    @Test void aDepartureKeepsItsReplyThroughTheHandoffToOrdinaryTravel() {
        assertEquals(DEPARTURE, DEPARTURE.observed(256, true, false)); // A look back.
        assertEquals(HANDOFF, HANDOFF.observed(256, true, false)); // The old selector still owns MOVE.
        assertEquals(ONWARD, ONWARD.observed(256, true, true));
        assertEquals(CLOSED, ONWARD.observed(256, true, false));
    }

    @Test void aFinishedOrOutOfRangeDepartureExpires() {
        for (var phase : new FarewellWindow[]{DEPARTURE, HANDOFF, ONWARD}) {
            assertEquals(CLOSED, phase.observed(256.01, true, true));
            assertEquals(CLOSED, phase.observed(16, false, true).observed(16, true, true));
        }
        assertEquals(CLOSED, ONWARD.observed(16, true, false).observed(16, true, true));
    }
}
