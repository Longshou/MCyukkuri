package io.github.longshou.mcyukkuri.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.longshou.mcyukkuri.social.ContestOdds.Level;
import io.github.longshou.mcyukkuri.social.ContestOdds.Side;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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
    void identicalBodiesAreAnEvenDraw() {
        double gap = ContestOdds.gap(side(1, 85), side(1, 85));
        assertEquals(0, gap, 1e-9);
        assertEquals(.5, ContestOdds.winChance(gap), 1e-9);
        assertTrue(ContestOdds.firstWins(gap, .49));
        assertFalse(ContestOdds.firstWins(gap, .51));
    }

    @Test
    void aBoldBodyAgainstAShyOneIsLopsidedAndCertain() {
        double gap = ContestOdds.gap(side(1, 95), side(1, 12));
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
    void temperRunsFromMildToFierce() {
        assertEquals(0, ContestOdds.temper(0, 100, 100), 1e-9);
        assertEquals(100, ContestOdds.temper(100, 0, 0), 1e-9);
        assertTrue(ContestOdds.temper(80, 20, 30) > ContestOdds.temper(40, 60, 60));
    }

    @Test
    void bondBrakeCoolsByAffinityOwnerAndKin() {
        assertEquals(0, ContestOdds.bondBrake(0, 0, false, false), 1e-9);
        assertEquals(25, ContestOdds.bondBrake(100, 100, false, false), 1e-9);
        assertEquals(-15, ContestOdds.bondBrake(-100, -100, false, false), 1e-9);
        assertTrue(ContestOdds.bondBrake(40, 40, true, false) > ContestOdds.bondBrake(40, 40, false, false));
        assertTrue(ContestOdds.bondBrake(40, 40, false, true) > ContestOdds.bondBrake(40, 40, false, false));
    }

    @Test
    void pairImportanceWeighsTheNeedierSideMore() {
        assertEquals(60, ContestOdds.pairImportance(100, 0));
        assertEquals(50, ContestOdds.pairImportance(50, 50));
        assertEquals(ContestOdds.pairImportance(20, 90), ContestOdds.pairImportance(90, 20));
    }

    @Test
    void heatStaysInRangeAndRisesWithTemperAndPrize() {
        assertEquals(0, ContestOdds.heat(0, 0, 25, -5), 1e-9);
        assertEquals(95, ContestOdds.heat(100, 100, 0, 5), 1e-9);
        assertTrue(ContestOdds.heat(90, 70, 0, 0) > ContestOdds.heat(20, 70, 0, 0));
        assertTrue(ContestOdds.heat(90, 70, 25, 0) < ContestOdds.heat(90, 70, 0, 0));
        assertEquals(ContestOdds.yieldGap(10), 47.5, 1e-9);
    }

    @Test
    void jitterIsStablePerPairAndSmall() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        int jitter = ContestOdds.jitter(a, b);
        assertEquals(jitter, ContestOdds.jitter(a, b));
        assertEquals(jitter, ContestOdds.jitter(b, a));
        for (int i = 0; i < 200; i++) {
            UUID other = new UUID(0, i);
            int value = ContestOdds.jitter(a, other);
            assertTrue(value >= -5 && value <= 5, "jitter out of range " + value);
        }
    }

    @Test
    void evenPairsNeedWarmthAndACloseGap() {
        assertTrue(ContestOdds.even(10, 40));
        assertFalse(ContestOdds.even(10, 10));
        assertFalse(ContestOdds.even(80, 40));
        assertFalse(ContestOdds.even(80, 100));
        assertTrue(ContestOdds.even(-10, 60));
    }

    @Test
    void levelFollowsGapHeatRuleAndFitness() {
        assertEquals(Level.POLITE_YIELD, ContestOdds.level(80, 40, true, true, true));
        assertEquals(Level.COMPLAIN_YIELD, ContestOdds.level(80, 40, true, true, false));
        assertEquals(Level.POLITE_YIELD, ContestOdds.level(10, 10, true, true, true));
        assertEquals(Level.SUMO_DECIDE, ContestOdds.level(10, 40, true, true, false));
        assertEquals(Level.PUFF_DECIDE, ContestOdds.level(10, 70, true, true, false));
        assertEquals(Level.PUFF_DECIDE, ContestOdds.level(10, 90, true, false, false));
        assertEquals(Level.ATTACK_DECIDE, ContestOdds.level(10, 90, true, true, false));
        assertEquals(Level.PUFF_DECIDE, ContestOdds.level(10, 70, false, true, false));
        assertEquals(Level.COMPLAIN_YIELD, ContestOdds.level(10, 40, false, true, false));
    }

    @Test
    void puffsGrowWithCloseness() {
        assertEquals(4, ContestOdds.puffRounds(0, 40));
        assertEquals(2, ContestOdds.puffRounds(100, 40));
        assertTrue(ContestOdds.puffRounds(10, 40) >= ContestOdds.puffRounds(40, 40));
    }

    @Test
    void onlySoundBodiesFight() {
        assertTrue(ContestOdds.fitToFight(20, 20, 100));
        assertTrue(ContestOdds.fitToFight(10, 20, 20));
        assertFalse(ContestOdds.fitToFight(9, 20, 100));
        assertFalse(ContestOdds.fitToFight(20, 20, 19));
        assertFalse(ContestOdds.fitToFight(3, 20, 100));
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
