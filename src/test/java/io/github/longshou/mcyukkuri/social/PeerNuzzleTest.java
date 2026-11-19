package io.github.longshou.mcyukkuri.social;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerNuzzleTest {
    @Test void preferLevelContactEvenWhenThePosesCouldTouchAcrossTheRise() {
        assertEquals(PeerNuzzle.LEVEL, PeerNuzzle.choose(true, true));
    }

    @Test void greetWhenNeitherPeerCanReachAContactStand() {
        assertEquals(PeerNuzzle.RISE, PeerNuzzle.choose(false, true));
        assertEquals(PeerNuzzle.GREET, PeerNuzzle.choose(false, false));
    }

    @Test void eagernessCanOffsetASlightlyLongerButNotAMuchLongerMove() {
        assertTrue(PeerNuzzle.effort(2.5, .1, 90) < PeerNuzzle.effort(2, .1, 10));
        assertTrue(PeerNuzzle.effort(4, .1, 90) > PeerNuzzle.effort(2, .1, 10));
    }

    @Test void interruptedContactCannotFinishTheLastBeat() {
        assertEquals(0, PeerNuzzle.contactAfter(139, false));
        assertEquals(140, PeerNuzzle.contactAfter(139, true));
    }
}
