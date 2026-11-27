package io.github.longshou.mcyukkuri.headwear;

import static org.junit.jupiter.api.Assertions.*;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.headwear.ScornLadder.TargetKind;
import io.github.longshou.mcyukkuri.headwear.ScornLadder.TargetProfile;
import io.github.longshou.mcyukkuri.headwear.ScornLadder.TaunterProfile;
import org.junit.jupiter.api.Test;

final class ScornLadderTest {
    private static final TaunterProfile HARSH_MARISA = new TaunterProfile(0, 0, 80, 50, true);
    private static final TaunterProfile PROUD_ALICE = new TaunterProfile(50, 70, 65, 65, false);
    private static final TaunterProfile FRIENDLY_REIMU = new TaunterProfile(80, 60, 50, 50, false);
    private static final TaunterProfile TIMID_CHEN = new TaunterProfile(55, 55, 15, 50, false);

    @Test
    void firstSightUsesHatlessScornWhenNotParroting() {
        var taunt = ScornLadder.choose(1, HARSH_MARISA, true, 0.1, "", "");
        assertEquals("hatless_scorn", taunt.event());
        assertEquals("fierce", taunt.face());
        assertNull(taunt.clip());
        assertFalse(taunt.quietSnub());
        assertFalse(taunt.bored());
        assertEquals(1, taunt.cooldownMultiplier());

        var proud = ScornLadder.choose(1, PROUD_ALICE, true, 0.1, "", "");
        assertEquals("hatless_scorn", proud.event());
        assertEquals("sneer", proud.face());
    }

    @Test
    void secondObserverDoesNotParrotFirstSightEvent() {
        var taunt = ScornLadder.choose(1, HARSH_MARISA, true, 0.1, "hatless_scorn", "");
        assertEquals("hatless_jeer", taunt.event());
        assertEquals("fierce", taunt.face());
        assertEquals(YukkuriAction.PUFF, taunt.clip());

        var proud = ScornLadder.choose(1, PROUD_ALICE, true, 0.1, "hatless_scorn", "");
        assertEquals("hatless_shoo", proud.event());
        assertEquals("pursed", proud.face());
        assertEquals(YukkuriAction.TURN, proud.clip());

        var friendly = ScornLadder.choose(1, FRIENDLY_REIMU, true, 0.1, "hatless_scorn", "");
        assertEquals("hatless_whisper", friendly.event());
        assertEquals("pursed", friendly.face());
        assertNull(friendly.clip());
    }

    @Test
    void roundsTwoAndThreeDifferByProfileAndNeverRepeat() {
        var harshR2 = ScornLadder.choose(2, HARSH_MARISA, true, 0.0, "hatless_scorn", "hatless_scorn");
        assertEquals("hatless_jeer", harshR2.event());
        assertEquals("fierce", harshR2.face());
        assertEquals(YukkuriAction.PUFF, harshR2.clip());

        // Same pair on round 3 must not repeat hatless_jeer.
        var harshR3 = ScornLadder.choose(3, HARSH_MARISA, true, 0.0, "hatless_jeer", "hatless_jeer");
        assertNotEquals("hatless_jeer", harshR3.event());
        assertEquals("hatless_shoo", harshR3.event());
        assertEquals("angry_stare", harshR3.face());
        assertEquals(YukkuriAction.TURN, harshR3.clip());

        var proudR2 = ScornLadder.choose(2, PROUD_ALICE, true, 0.0, "hatless_scorn", "hatless_scorn");
        assertEquals("hatless_shoo", proudR2.event());
        assertEquals("pursed", proudR2.face());
        assertEquals(YukkuriAction.TURN, proudR2.clip());
    }

    @Test
    void whisperRequiresThirdPartyWitness() {
        var withWitness = ScornLadder.choose(2, TIMID_CHEN, true, 0.0, "hatless_scorn", "hatless_scorn");
        assertEquals("hatless_whisper", withWitness.event());
        assertFalse(withWitness.quietSnub());

        var alone = ScornLadder.choose(2, TIMID_CHEN, false, 0.0, "hatless_scorn", "hatless_scorn");
        assertEquals("", alone.event());
        assertEquals("pursed", alone.face());
        assertTrue(alone.quietSnub());
    }

    @Test
    void quietSnubWhenRollExceedsSpeakChance() {
        double chance = ScornLadder.speakChance(2, FRIENDLY_REIMU);
        assertTrue(chance > 0.15 && chance < 0.6);
        var snub = ScornLadder.choose(2, FRIENDLY_REIMU, true, 0.99, "hatless_scorn", "hatless_scorn");
        assertTrue(snub.quietSnub());
        assertFalse(snub.bored());
        assertEquals("", snub.event());
        assertEquals("pursed", snub.face());
    }

    @Test
    void roundFourPlusBoredomDoublesCooldownAndEscalatesOnlyHarshNonKin() {
        var boredFriendly = ScornLadder.choose(4, FRIENDLY_REIMU, true, 0.0, "hatless_whisper", "hatless_whisper");
        assertTrue(boredFriendly.bored());
        assertTrue(boredFriendly.quietSnub());
        assertEquals("", boredFriendly.event());
        assertEquals("dull", boredFriendly.face());
        assertEquals(2, boredFriendly.cooldownMultiplier());

        var boredHarsh = ScornLadder.choose(5, HARSH_MARISA, true, 0.0, "hatless_jeer", "hatless_jeer");
        assertTrue(boredHarsh.bored());
        assertEquals("down_angry", boredHarsh.face());
        assertEquals(2, boredHarsh.cooldownMultiplier());

        assertEquals(0, ScornLadder.escalationBonus(3, true, false));
        assertEquals(40, ScornLadder.escalationBonus(4, true, false));
        assertEquals(0, ScornLadder.escalationBonus(4, true, true));
        assertEquals(0, ScornLadder.escalationBonus(4, false, false));
    }

    @Test
    void targetBranchesByStatsAndHistory() {
        // Round 1: shock (teary, 1/3 chance scorn_hurt).
        var first = ScornLadder.chooseTarget(1, false, new TargetProfile(95, 65, 0, false, false), 0.1);
        assertEquals(TargetKind.SHOCK, first.kind());
        assertEquals("teary", first.face());
        assertEquals("scorn_hurt", first.event());

        // Repeated: bold + high esteem answers back with hatless_answer.
        var answer = ScornLadder.chooseTarget(2, true, new TargetProfile(95, 65, 0, false, false), 0.9);
        assertEquals(TargetKind.ANSWER, answer.kind());
        assertEquals("cheek_puff", answer.face());
        assertEquals(YukkuriAction.PUFF, answer.clip());
        assertEquals("hatless_answer", answer.event());
        assertFalse(answer.avoidTaunter());

        // Starving bold target does not answer back.
        var starving = ScornLadder.chooseTarget(2, true, new TargetProfile(95, 65, 0, true, false), 0.9);
        assertNotEquals(TargetKind.ANSWER, starving.kind());

        // Repeated: timid + low esteem goes numb (dull, DROOP, no line, avoids taunter).
        var numb = ScornLadder.chooseTarget(2, true, new TargetProfile(15, 35, 10, false, false), 0.1);
        assertEquals(TargetKind.NUMB, numb.kind());
        assertEquals("dull", numb.face());
        assertEquals(YukkuriAction.DROOP, numb.clip());
        assertEquals("", numb.event());
        assertTrue(numb.avoidTaunter());

        // Repeated with high fear of taunter leaves.
        var leave = ScornLadder.chooseTarget(2, true, new TargetProfile(40, 45, 55, false, false), 0.1);
        assertEquals(TargetKind.LEAVE, leave.kind());
        assertEquals("teary", leave.face());
        assertEquals("", leave.event());
        assertTrue(leave.avoidTaunter());
    }
}
