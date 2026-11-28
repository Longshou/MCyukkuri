package io.github.longshou.mcyukkuri.dialogue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SpeechTurnTest {
    @Test void speaksImmediatelyWhenNeighborsSilentOrPastBreath() {
        assertEquals(new SpeechTurn.Decision(SpeechTurn.Action.SPEAK_NOW, 100),
                SpeechTurn.evaluate(100, 0, 100 + SpeechTurn.MAX_WAIT));
        assertEquals(new SpeechTurn.Decision(SpeechTurn.Action.SPEAK_NOW, 100),
                SpeechTurn.evaluate(100, 90, 100 + SpeechTurn.MAX_WAIT));
        assertEquals(100, SpeechTurn.nextSlot(100, 90));
    }

    @Test void defersUntilNeighborFinishesPlusBreath() {
        var decision = SpeechTurn.evaluate(100, 140, 100 + SpeechTurn.MAX_WAIT);
        assertTrue(decision.defer());
        assertEquals(150, decision.dueTick());

        var duringBreath = SpeechTurn.evaluate(145, 140, 145 + SpeechTurn.MAX_WAIT);
        assertTrue(duringBreath.defer());
        assertEquals(150, duringBreath.dueTick());
    }

    @Test void staggersTwoPerformersAndDropsThirdCrowdEcho() {
        long expires = 100 + SpeechTurn.MAX_WAIT;
        // Speaker 1 speaks [100..180].
        long speaker1BusyUntil = 180;
        // Speaker 2 sees busyUntil=180 -> slot 190 <= 210 -> defers to 190 with plannedEnd=270.
        var speaker2 = SpeechTurn.evaluate(100, speaker1BusyUntil, expires);
        assertTrue(speaker2.defer());
        assertEquals(190, speaker2.dueTick());
        long speaker2BusyUntil = speaker2.dueTick() + 80;
        // Speaker 3 sees busyUntil=270 -> slot 280 > 210 -> drops cleanly.
        var speaker3 = SpeechTurn.evaluate(100, speaker2BusyUntil, expires);
        assertTrue(speaker3.drop());
        assertEquals(-1, speaker3.dueTick());
    }

    @Test void boundedReDeferralDropsOnceCeilingPasses() {
        long originalExpires = 100 + SpeechTurn.MAX_WAIT;
        var first = SpeechTurn.evaluate(100, 170, originalExpires);
        assertTrue(first.defer());
        assertEquals(180, first.dueTick());

        // Neighbor gets interrupted/replaced by another longer speaker; re-evaluate keeps originalExpires.
        var second = SpeechTurn.evaluate(180, 210, originalExpires);
        assertTrue(second.drop());
    }
}
