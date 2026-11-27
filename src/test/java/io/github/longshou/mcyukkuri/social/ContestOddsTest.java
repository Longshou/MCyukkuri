package io.github.longshou.mcyukkuri.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.longshou.mcyukkuri.social.ContestOdds.Process;
import io.github.longshou.mcyukkuri.social.ContestOdds.Side;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContestOddsTest {
    private static Side side(double might, int boldness) { return new Side(might, boldness, 70, 0, 5); }

    @Test
    void mightKeepsATiredBodyAContestant() {
        assertEquals(ContestOdds.might(1, 1, 1, 0), .15, 1e-9);
        assertTrue(ContestOdds.might(1, 1, 1, 100) > ContestOdds.might(1, 1, 1, 40));
        assertTrue(ContestOdds.might(1, 1, .5, 100) < ContestOdds.might(1, 1, 1, 100));
    }

    @Test
    void identicalBodiesAreAnEvenTussle() {
        double gap = ContestOdds.gap(side(1, 85), side(1, 85));
        assertEquals(0, gap, 1e-9);
        assertEquals(Process.TUSSLE, ContestOdds.process(gap, true));
        assertEquals(.5, ContestOdds.winChance(gap), 1e-9);
        assertEquals(ContestOdds.MAX_PUSHES, ContestOdds.pushes(gap));
    }

    @Test
    void aBoldBodyAgainstAShyOneIsLopsidedAndCertain() {
        double gap = ContestOdds.gap(side(1, 95), side(1, 12));
        assertEquals(Process.LOPSIDED, ContestOdds.process(gap, true));
        assertTrue(ContestOdds.firstWins(gap, .999));
        assertFalse(ContestOdds.firstWins(-gap, 0));
    }

    @Test
    void theGapIsAntisymmetricAndTheDrawSplitsIt() {
        var a = side(1.2, 60);
        var b = side(.8, 70);
        assertEquals(ContestOdds.gap(a, b), -ContestOdds.gap(b, a), 1e-9);
        double gap = ContestOdds.gap(a, b);
        assertTrue(Math.abs(gap) < ContestOdds.LOPSIDED_GAP);
        assertTrue(ContestOdds.firstWins(gap, ContestOdds.winChance(gap) - .01));
        assertFalse(ContestOdds.firstWins(gap, ContestOdds.winChance(gap) + .01));
    }

    @Test
    void aBoldSmallBodyCanBeatATimidBigOne() {
        var small = new Side(.6, 90, 40, 0, 40);
        var big = new Side(1.0, 20, 80, 30, 5);
        assertTrue(ContestOdds.gap(small, big) > 0);
    }

    @Test
    void needAndFearShiftTheScore() {
        var calm = new Side(1, 50, 50, 0, 0);
        assertTrue(ContestOdds.will(new Side(1, 50, 50, 0, 80)) > ContestOdds.will(calm));
        assertTrue(ContestOdds.will(new Side(1, 50, 50, 60, 0)) < ContestOdds.will(calm));
        assertTrue(ContestOdds.will(new Side(1, 50, 90, 0, 0)) < ContestOdds.will(calm));
    }

    @Test
    void ownedPairsNeverTussle() {
        assertEquals(Process.LOPSIDED, ContestOdds.process(0, false));
    }

    @Test
    void pushesFollowCloseness() {
        assertTrue(ContestOdds.pushes(0) > ContestOdds.pushes(40));
        assertEquals(ContestOdds.MIN_PUSHES, ContestOdds.pushes(ContestOdds.LOPSIDED_GAP));
    }

    @Test
    void sumoPushedFaceCoversAllFourFacesAndNeverRepeatsPrevious() {
        Set<String> expected = Set.of("ashamed", "surprise", "teary_pant", "angry");
        for (int boldness : new int[]{10, 50, 95}) {
            for (int selfControl : new int[]{15, 85}) {
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < 100; i++) {
                    String face = ContestOdds.sumoPushedFace(boldness, selfControl, "", i / 100.0);
                    assertTrue(expected.contains(face), "unexpected face " + face);
                    seen.add(face);
                }
                assertEquals(expected, seen, "all four faces must stay reachable for boldness=" + boldness + " selfControl=" + selfControl);
            }
        }
        for (String previous : expected) {
            for (int i = 0; i < 50; i++) {
                String next = ContestOdds.sumoPushedFace(60, 50, previous, i / 50.0);
                assertFalse(previous.equals(next), "repeated previous face " + previous);
            }
        }
    }

    @Test
    void sumoPushedFaceLeansAngryForBoldAndAshamedForTimid() {
        int boldAngry = 0, timidAngry = 0, timidAshamed = 0, boldAshamed = 0;
        for (int i = 0; i < 200; i++) {
            double roll = i / 200.0;
            String boldFace = ContestOdds.sumoPushedFace(95, 20, "", roll);
            String timidFace = ContestOdds.sumoPushedFace(10, 85, "", roll);
            if ("angry".equals(boldFace)) boldAngry++;
            if ("angry".equals(timidFace)) timidAngry++;
            if ("ashamed".equals(timidFace)) timidAshamed++;
            if ("ashamed".equals(boldFace)) boldAshamed++;
        }
        assertTrue(boldAngry > timidAngry);
        assertTrue(timidAshamed > boldAshamed);
    }

    @Test
    void contestLoserFaceSplitsByPersonalityAndNeverPuffs() {
        assertEquals("teary", ContestOdds.contestLoserFace(12, 80, 70));
        assertEquals("down_angry", ContestOdds.contestLoserFace(85, 30, 70));
        assertEquals("down_angry", ContestOdds.contestLoserFace(85, 80, 30));
        assertEquals("hmph", ContestOdds.contestLoserFace(85, 70, 70));
        for (int b = 0; b <= 100; b += 10) {
            for (int c = 0; c <= 100; c += 25) {
                for (int f = 0; f <= 100; f += 25) {
                    assertFalse("cheek_puff".equals(ContestOdds.contestLoserFace(b, c, f)));
                }
            }
        }
    }
}
