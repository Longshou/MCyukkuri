package io.github.longshou.mcyukkuri.pet.rules;

import static io.github.longshou.mcyukkuri.pet.rules.BreedingReaction.BOW_ONLY;
import static io.github.longshou.mcyukkuri.pet.rules.BreedingReaction.choose;
import static org.junit.jupiter.api.Assertions.*;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament;
import org.junit.jupiter.api.Test;

class BreedingReactionTest {
    // intelligence, friendliness, boldness, attachment, selfControl
    private static final Temperament BOLD = new Temperament(55, 70, 90, 40, 40);
    private static final Temperament TIMID = new Temperament(40, 70, 10, 40, 70);
    private static final Temperament SHARP_CLINGING = new Temperament(85, 70, 30, 85, 60);

    @Test void allowedBoldPentUpJumpsForJoyAndLooksAtItsPartner() {
        var beat = choose(true, 90, 17, 0, BOLD);
        assertEquals(YukkuriAction.JOY_JUMP, beat.action());
        assertEquals("happy", beat.face());
        assertEquals("permit_glad", beat.event());
        assertTrue(beat.toward());
    }

    @Test void allowedTimidOrMidDriveBlushesAndFaceFollowsDrive() {
        var pressing = choose(true, 70, 92, 0, TIMID);
        assertEquals(YukkuriAction.SHY, pressing.action());
        assertEquals("flushed_smile", pressing.face());
        assertEquals("permit_glad", pressing.event());
        // mid drive (pentUp 45 -> ramp .4): a bold pet is shy too, with the quieter face
        var mid = choose(true, 45, 17, 0, BOLD);
        assertEquals(YukkuriAction.SHY, mid.action());
        assertEquals("closed_smile", mid.face());
    }

    @Test void allowedLowDriveJustBows() {
        assertSame(BOW_ONLY, choose(true, 5, 17, 0, BOLD));
        assertSame(BOW_ONLY, choose(true, 34, 92, 0, TIMID));
    }

    @Test void deniedPentUpDisobedientPuffsAndHuffs() {
        var beat = choose(false, 90, 17, 0, BOLD);
        assertEquals(YukkuriAction.PUFF, beat.action());
        assertEquals("cheek_puff", beat.face());
        assertEquals("sulk_huff", beat.event());
        assertFalse(beat.toward());
    }

    @Test void deniedObedientDroopsWithoutALine() {
        var beat = choose(false, 70, 92, 0, TIMID);
        assertEquals(YukkuriAction.DROOP, beat.action());
        assertEquals("teary", beat.face());
        assertEquals("", beat.event());
    }

    @Test void deniedFearOfTheOwnerCowersWhateverTheDrive() {
        assertEquals(YukkuriAction.THUNDER_COWER, choose(false, 90, 17, 30, BOLD).action());
        assertEquals(YukkuriAction.THUNDER_COWER, choose(false, 0, 92, 60, TIMID).action());
        assertNotEquals(YukkuriAction.THUNDER_COWER, choose(false, 90, 17, 29, BOLD).action());
    }

    @Test void deniedSharpAttachedAcceptsAshamed() {
        var beat = choose(false, 60, 75, 0, SHARP_CLINGING);
        assertEquals(YukkuriAction.BOW, beat.action());
        assertEquals("ashamed", beat.face());
        assertEquals("", beat.event());
    }

    @Test void deniedLowDriveAndMidObedienceDisobedientJustBow() {
        assertSame(BOW_ONLY, choose(false, 5, 92, 0, TIMID));
        // pushy but only mid drive and not obedient: no show of defiance
        assertSame(BOW_ONLY, choose(false, 45, 17, 0, BOLD));
    }

    @Test void twoPetsWithDifferentNumbersAnswerDifferently() {
        assertNotEquals(choose(true, 90, 17, 0, BOLD).name(), choose(true, 70, 92, 0, TIMID).name());
        assertNotEquals(choose(false, 90, 17, 0, BOLD).name(), choose(false, 70, 92, 0, TIMID).name());
    }

    @Test void everyBeatIsDeterministic() {
        for (boolean allowed : new boolean[] {true, false})
            for (double pent = 0; pent <= 100; pent += 5)
                for (int obedience = 0; obedience <= 100; obedience += 25)
                    for (int fear : new int[] {0, 29, 30, 80})
                        assertEquals(choose(allowed, pent, obedience, fear, SHARP_CLINGING), choose(allowed, pent, obedience, fear, SHARP_CLINGING));
    }
}
