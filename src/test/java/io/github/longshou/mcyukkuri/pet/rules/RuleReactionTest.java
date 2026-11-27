package io.github.longshou.mcyukkuri.pet.rules;

import static io.github.longshou.mcyukkuri.pet.rules.RuleReaction.choose;
import static io.github.longshou.mcyukkuri.pet.rules.RuleReaction.hold;
import static io.github.longshou.mcyukkuri.pet.rules.RuleReaction.witness;
import static org.junit.jupiter.api.Assertions.*;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleReactionTest {
    // intelligence, friendliness, boldness, attachment, selfControl
    private static final Temperament BOLD = new Temperament(55, 70, 90, 40, 40);
    private static final Temperament TIMID = new Temperament(40, 70, 10, 40, 70);
    private static final Temperament SHARP_CLINGING = new Temperament(85, 70, 30, 85, 60);
    private static final String GLAD = "permit_glad";
    /** Blank or uncanny faces banned from the redesigned house-rule performances. */
    private static final Set<String> BANNED_FACES = Set.of("look_down", "pursed", "endure");

    private static List<String> names(List<RuleReaction.Beat> beats) { return beats.stream().map(RuleReaction.Beat::name).toList(); }

    @Test void loosenedBoldPressingJumpsForJoyGlancesAtPartnerThenBowsInThanks() {
        var beats = choose(true, .9, 17, 0, BOLD, GLAD);
        assertEquals(List.of("loosen_joy", "loosen_thanks"), names(beats));
        assertEquals(YukkuriAction.JOY_JUMP, beats.get(0).action());
        assertEquals("happy", beats.get(0).face());
        assertEquals(GLAD, beats.get(0).event());
        assertTrue(beats.get(0).toward());
        assertEquals("rule_grateful", beats.get(1).event());
        assertEquals("open_smile", beats.get(1).face());
        assertEquals(YukkuriAction.BOW, beats.get(1).action());
    }

    @Test void loosenedTimidOrMidDriveBlushesAndFearfulAddsRelievedBow() {
        var pressing = choose(true, .7, 92, 0, TIMID, GLAD);
        assertEquals(List.of("loosen_shy"), names(pressing));
        assertEquals("flushed_smile", pressing.get(0).face());
        assertEquals("shy_peek", choose(true, .45, 17, 0, BOLD, GLAD).get(0).face());
        var fearful = choose(true, .7, 92, 40, TIMID, GLAD);
        assertEquals(List.of("loosen_shy", "loosen_thanks"), names(fearful));
        assertEquals("teary_smile", fearful.get(1).face());
    }

    @Test void loosenedWithNoUrgeUsesCalmRuleLoosenLine() {
        var beats = choose(true, .05, 17, 0, BOLD, GLAD);
        assertEquals(List.of("loosen_nod"), names(beats));
        assertEquals("rule_loosen", beats.get(0).event());
        assertFalse(beats.get(0).toward());
    }

    @Test void tightenedPressingDisobedientPuffsProtestsThenYieldsAshamed() {
        var beats = choose(false, .9, 17, 0, BOLD, GLAD);
        assertEquals(List.of("tighten_puff", "tighten_yield"), names(beats));
        assertEquals(YukkuriAction.PUFF, beats.get(0).action());
        assertEquals("cheek_puff", beats.get(0).face());
        assertEquals("rule_sulk", beats.get(0).event());
        assertEquals(YukkuriAction.DROOP, beats.get(1).action());
        assertEquals("ashamed", beats.get(1).face());
        assertEquals("rule_yield", beats.get(1).event());
    }

    @Test void tightenedObedientWithUrgeDroopsSadlyWithDedicatedLine() {
        var beat = choose(false, .7, 92, 0, TIMID, GLAD).get(0);
        assertEquals("tighten_droop", beat.name());
        assertEquals(YukkuriAction.DROOP, beat.action());
        assertEquals("side_teary", beat.face());
        assertEquals("rule_tighten_sad", beat.event());
    }

    @Test void tightenedFearOfTheOwnerCowersWhateverTheUrge() {
        assertEquals(YukkuriAction.THUNDER_COWER, choose(false, .9, 17, 30, BOLD, GLAD).get(0).action());
        assertEquals(YukkuriAction.THUNDER_COWER, choose(false, 0, 92, 60, TIMID, GLAD).get(0).action());
        assertNotEquals(YukkuriAction.THUNDER_COWER, choose(false, .9, 17, 29, BOLD, GLAD).get(0).action());
        assertEquals("rule_cower", choose(false, 0, 92, 60, TIMID, GLAD).get(0).event());
    }

    @Test void tightenedSharpAttachedAcceptsAshamed() {
        var beat = choose(false, .6, 75, 0, SHARP_CLINGING, GLAD).get(0);
        assertEquals("tighten_accept", beat.name());
        assertEquals(YukkuriAction.BOW, beat.action());
        assertEquals("ashamed", beat.face());
        assertEquals("rule_tighten", beat.event());
    }

    @Test void witnessPetsReactToHousemateChangesWithoutIndifferentSpam() {
        var enviousBold = witness(true, true, .8, 30, 0, BOLD);
        assertEquals(List.of("witness_envy"), names(enviousBold));
        assertEquals("hmph", enviousBold.get(0).face());
        assertEquals("rule_witness_envy", enviousBold.get(0).event());

        var enviousTimid = witness(true, true, .8, 80, 0, TIMID);
        assertEquals(List.of("witness_envy"), names(enviousTimid));
        assertEquals("side_teary", enviousTimid.get(0).face());

        assertTrue(witness(true, false, .8, 30, 0, BOLD).isEmpty());
        assertTrue(witness(true, true, .1, 30, 0, BOLD).isEmpty());

        var wary = witness(false, true, .2, 80, 35, TIMID);
        assertEquals(List.of("witness_wary"), names(wary));
        assertEquals("afraid", wary.get(0).face());

        var sympathy = witness(false, true, .8, 30, 0, BOLD);
        assertEquals(List.of("witness_sympathy"), names(sympathy));
        assertEquals("sad", sympathy.get(0).face());
    }

    @Test void everyRuleBeatWitnessBeatAndHoldBeatUsesExpressiveFacesAndValidLength() {
        for (boolean loosened : new boolean[] {true, false})
            for (double urge : new double[] {0, .3, .5, .9})
                for (int obedience : new int[] {10, 40, 60, 90})
                    for (int fear : new int[] {0, 29, 30, 80})
                        for (var temper : new Temperament[] {BOLD, TIMID, SHARP_CLINGING}) {
                            var beats = choose(loosened, urge, obedience, fear, temper, GLAD);
                            assertFalse(beats.isEmpty());
                            for (var beat : beats) {
                                assertFalse(beat.event().isEmpty(), beat.name());
                                assertFalse(BANNED_FACES.contains(beat.face()), beat.name() + ":" + beat.face());
                                assertTrue(beat.ticks() >= 50, beat.name());
                            }
                            for (boolean hasRule : new boolean[] {true, false}) {
                                for (var w : witness(loosened, hasRule, urge, obedience, fear, temper)) {
                                    assertFalse(w.event().isEmpty(), w.name());
                                    assertFalse(BANNED_FACES.contains(w.face()), w.name() + ":" + w.face());
                                    assertTrue(w.ticks() >= 50, w.name());
                                }
                            }
                        }
        for (var rule : HouseRule.values())
            for (double pressure : new double[] {.1, .5, .85})
                for (int obedience : new int[] {20, 75})
                    for (int fear : new int[] {0, 35})
                        for (var temper : new Temperament[] {BOLD, TIMID, SHARP_CLINGING}) {
                            var beat = hold(rule, pressure, obedience, fear, temper);
                            assertFalse(beat.event().isEmpty(), beat.name());
                            assertFalse(BANNED_FACES.contains(beat.face()), beat.name() + ":" + beat.face());
                            assertTrue(beat.ticks() >= 45, beat.name());
                        }
    }

    @Test void twoPetsWithDifferentNumbersAnswerDifferentlyAndDeterministically() {
        assertNotEquals(names(choose(true, .9, 17, 0, BOLD, GLAD)), names(choose(true, .7, 92, 0, TIMID, GLAD)));
        assertNotEquals(names(choose(false, .9, 17, 0, BOLD, GLAD)), names(choose(false, .7, 92, 0, TIMID, GLAD)));
        assertEquals(choose(false, .9, 17, 0, BOLD, GLAD), choose(false, .9, 17, 0, BOLD, GLAD));
    }
}
