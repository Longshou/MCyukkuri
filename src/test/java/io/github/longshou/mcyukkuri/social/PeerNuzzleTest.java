package io.github.longshou.mcyukkuri.social;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    @Test void firstAndLastCreditPublishBothMirroredPosesAtTheResolvedSpacing() {
        for (int previous : new int[]{0, 139}) {
            var shown = new ArrayList<String>();
            int credited = PeerNuzzle.contactAfter(previous, true, .94f,
                    (clip, frame, spacing) -> shown.add("mover " + clip + " " + frame + " " + spacing),
                    (clip, frame, spacing) -> shown.add("anchor " + clip + " " + frame + " " + spacing));
            assertEquals(List.of("anchor snuggle_left " + previous + " 0.94",
                    "mover snuggle_right " + previous + " 0.94"), shown);
            assertEquals(previous + 1, credited);
        }
    }

    @Test void interruptedContactCannotFinishTheLastBeat() {
        PeerNuzzle.Display unseen = (clip, frame, spacing) -> fail("no contact pose");
        assertEquals(0, PeerNuzzle.contactAfter(139, false, .94f, unseen, unseen));
    }
}
