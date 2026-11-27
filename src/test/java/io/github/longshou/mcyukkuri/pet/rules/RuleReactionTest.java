package io.github.longshou.mcyukkuri.pet.rules;

import static io.github.longshou.mcyukkuri.pet.rules.RuleReaction.choose;
import static org.junit.jupiter.api.Assertions.*;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleReactionTest {
    // intelligence, friendliness, boldness, attachment, selfControl
    private static final Temperament BOLD = new Temperament(55, 70, 90, 40, 40);
    private static final Temperament TIMID = new Temperament(40, 70, 10, 40, 70);
    private static final Temperament SHARP_CLINGING = new Temperament(85, 70, 30, 85, 60);
    private static final String GLAD = "permit_glad";

    private static List<String> names(List<RuleReaction.Beat> beats) { return beats.stream().map(RuleReaction.Beat::name).toList(); }

    @Test void loosenedBoldPressingJumpsForJoyGlancesAtTheOneItWantsThenBowsInThanks() {
        var beats = choose(true, .9, 17, 0, BOLD, GLAD);
        assertEquals(List.of("loosen_joy", "loosen_thanks"), names(beats));
        assertEquals(YukkuriAction.JOY_JUMP, beats.get(0).action());
        assertEquals("happy", beats.get(0).face());
        assertEquals(GLAD, beats.get(0).event());
        assertTrue(beats.get(0).toward());
        assertEquals("rule_grateful", beats.get(1).event());
        assertEquals(YukkuriAction.BOW, beats.get(1).action());
    }

    @Test void loosenedTimidOrMidDriveBlushesAndFaceFollowsDrive() {
        var pressing = choose(true, .7, 92, 0, TIMID, GLAD);
        assertEquals(List.of("loosen_shy"), names(pressing));
        assertEquals("flushed_smile", pressing.get(0).face());
        assertEquals("closed_smile", choose(true, .45, 17, 0, BOLD, GLAD).get(0).face());
    }

    @Test void loosenedWithNoUrgeStillNodsItsThanksWithTheGivenLine() {
        var beats = choose(true, .05, 17, 0, BOLD, "rule_loosen");
        assertEquals(List.of("loosen_nod"), names(beats));
        assertEquals("rule_loosen", beats.get(0).event());
        assertFalse(beats.get(0).toward());
    }

    @Test void tightenedPressingDisobedientPuffsAndHuffsThenYields() {
        var beats = choose(false, .9, 17, 0, BOLD, GLAD);
        assertEquals(List.of("tighten_puff", "tighten_yield"), names(beats));
        assertEquals(YukkuriAction.PUFF, beats.get(0).action());
        assertEquals("cheek_puff", beats.get(0).face());
        assertEquals("sulk_huff", beats.get(0).event());
        assertEquals(YukkuriAction.BOW, beats.get(1).action());
        assertEquals("rule_yield", beats.get(1).event());
    }

    @Test void tightenedObedientDroopsWithItsLine() {
        var beat = choose(false, .7, 92, 0, TIMID, GLAD).get(0);
        assertEquals(YukkuriAction.DROOP, beat.action());
        assertEquals("teary", beat.face());
        assertEquals("rule_tighten", beat.event());
    }

    @Test void tightenedFearOfTheOwnerCowersWhateverTheUrge() {
        assertEquals(YukkuriAction.THUNDER_COWER, choose(false, .9, 17, 30, BOLD, GLAD).get(0).action());
        assertEquals(YukkuriAction.THUNDER_COWER, choose(false, 0, 92, 60, TIMID, GLAD).get(0).action());
        assertNotEquals(YukkuriAction.THUNDER_COWER, choose(false, .9, 17, 29, BOLD, GLAD).get(0).action());
        assertEquals("rule_cower", choose(false, 0, 92, 60, TIMID, GLAD).get(0).event());
    }

    @Test void tightenedSharpAttachedAcceptsAshamed() {
        var beat = choose(false, .6, 75, 0, SHARP_CLINGING, GLAD).get(0);
        assertEquals(YukkuriAction.BOW, beat.action());
        assertEquals("ashamed", beat.face());
        assertEquals("rule_tighten", beat.event());
    }

    @Test void everyPetShowsAtLeastOneBeatWithALineAndNobodyTreatsTheOwnerAsAnEqual() {
        for (boolean loosened : new boolean[] {true, false})
            for (double urge : new double[] {0, .3, .5, .9})
                for (int obedience : new int[] {10, 40, 60, 90})
                    for (int fear : new int[] {0, 29, 30, 80})
                        for (var temper : new Temperament[] {BOLD, TIMID, SHARP_CLINGING}) {
                            var beats = choose(loosened, urge, obedience, fear, temper, GLAD);
                            assertFalse(beats.isEmpty());
                            for (var beat : beats) {
                                assertFalse(beat.event().isEmpty(), beat.name());
                                assertFalse(beat.face().isEmpty(), beat.name());
                                assertTrue(beat.ticks() >= 50, beat.name());
                            }
                        }
    }

    @Test void twoPetsWithDifferentNumbersAnswerDifferently() {
        assertNotEquals(names(choose(true, .9, 17, 0, BOLD, GLAD)), names(choose(true, .7, 92, 0, TIMID, GLAD)));
        assertNotEquals(names(choose(false, .9, 17, 0, BOLD, GLAD)), names(choose(false, .7, 92, 0, TIMID, GLAD)));
    }

    @Test void everyBeatIsDeterministic() {
        assertEquals(choose(false, .9, 17, 0, BOLD, GLAD), choose(false, .9, 17, 0, BOLD, GLAD));
    }
}
