package io.github.longshou.mcyukkuri.social;

import static org.junit.jupiter.api.Assertions.*;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.mind.Ousting.Answer;
import io.github.longshou.mcyukkuri.social.SeveranceReaction.SiblingStance;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeveranceReactionTest {
    private static final Set<String> SMILES = Set.of("neutral", "happy", "open_smile", "closed_smile", "satisfied", "teary_smile");
    private static final Set<String> TEARS = Set.of("teary", "cry", "side_teary", "teary_frown", "puff_teary", "open_cry");

    @Test void aloofChildNeverShowsTearsAndGetsColdScoff() {
        var sulkKid = new Temperament(55, 60, 45, 20, 70);
        assertEquals("flick_dull", SeveranceReaction.childListenFace(sulkKid, false, Answer.SHRUG));
        assertFalse(TEARS.contains(SeveranceReaction.childListenFace(sulkKid, false, Answer.SHRUG)));
        var open = SeveranceReaction.childOpening(sulkKid, false, false, Answer.SHRUG);
        assertEquals("scoff", open.beat());
        assertEquals("down_angry", open.face());
        assertEquals(YukkuriAction.MOOD_FLICK, open.clip());
        assertEquals("sever_cold_scoff", open.line());
        assertFalse(TEARS.contains(open.face()));
    }

    @Test void pleadingAndFreezingChildrenOpenWithShockThenStatFollowUp() {
        var pleadKid = new Temperament(55, 60, 45, 75, 70);
        assertEquals("surprise", SeveranceReaction.childListenFace(pleadKid, false, Answer.FAREWELL));
        var pleadOpen = SeveranceReaction.childOpening(pleadKid, false, true, Answer.FAREWELL);
        assertEquals("shock", pleadOpen.beat());
        assertEquals("surprise", pleadOpen.face());
        assertEquals(YukkuriAction.STARTLE, pleadOpen.clip());
        assertEquals("sever_shock", pleadOpen.line());
        var pleadFollow = SeveranceReaction.childFollow(pleadKid, true);
        assertEquals("plead", pleadFollow.beat());
        assertEquals("teary", pleadFollow.face());
        assertEquals("sever_plea", pleadFollow.line());

        var freezeTimid = new Temperament(55, 60, 35, 45, 70);
        var freezeFollow = SeveranceReaction.childFollow(freezeTimid, false);
        assertEquals("stare", freezeFollow.beat());
        assertEquals("teary", freezeFollow.face());
        assertEquals("sever_grievance", freezeFollow.line());

        var freezePlain = new Temperament(55, 60, 50, 45, 70);
        assertEquals("teary_frown", SeveranceReaction.childFollow(freezePlain, false).face());
    }

    @Test void protestingChildOpensWithAngryProtest() {
        var cocky = new Temperament(55, 20, 80, 40, 20);
        assertEquals("angry", SeveranceReaction.childListenFace(cocky, true, Answer.CURSE));
        var open = SeveranceReaction.childOpening(cocky, true, false, Answer.CURSE);
        assertEquals("resist", open.beat());
        assertEquals("angry", open.face());
        assertEquals(YukkuriAction.STAND_GROUND, open.clip());
        assertEquals("sever_protest", open.line());
    }

    @Test void speechStyleFollowsTraitsRegardlessOfCapsizeFright() {
        assertEquals("timid", SeveranceReaction.speechStyle(new Temperament(55, 60, 30, 50, 60)));
        assertEquals("plain", SeveranceReaction.speechStyle(new Temperament(55, 60, 45, 75, 70)));
        assertEquals("proud", SeveranceReaction.speechStyle(new Temperament(55, 60, 65, 50, 60)));
        assertEquals("proud", SeveranceReaction.speechStyle(new Temperament(55, 20, 45, 30, 40)));
    }

    @Test void siblingStancesCoverAllFiveProfilesAndNeverEmitSmiles() {
        var sorrow = new Temperament(55, 75, 45, 70, 70);
        var scared = new Temperament(55, 65, 25, 50, 70);
        var protest = new Temperament(55, 75, 70, 70, 70);
        var cold = new Temperament(55, 25, 55, 20, 70);
        var uneasy = new Temperament(55, 50, 50, 35, 70);

        assertEquals(SiblingStance.SORROW, SeveranceReaction.siblingStance(sorrow));
        assertEquals(SiblingStance.SCARED, SeveranceReaction.siblingStance(scared));
        assertEquals(SiblingStance.PROTEST, SeveranceReaction.siblingStance(protest));
        assertEquals(SiblingStance.COLD, SeveranceReaction.siblingStance(cold));
        assertEquals(SiblingStance.UNEASY, SeveranceReaction.siblingStance(uneasy));

        assertTrue(SiblingStance.SORROW.partsWithGrief(sorrow));
        assertTrue(SiblingStance.PROTEST.partsWithGrief(protest));
        assertTrue(SiblingStance.SCARED.partsWithGrief(scared));
        assertFalse(SiblingStance.COLD.partsWithGrief(cold));
        assertFalse(SiblingStance.UNEASY.partsWithGrief(uneasy));

        for (SiblingStance stance : SiblingStance.values()) {
            assertFalse(SMILES.contains(stance.verdictFace()), stance + " verdictFace");
            assertFalse(SMILES.contains(stance.rebuffFace()), stance + " rebuffFace");
            assertFalse(SMILES.contains(stance.turnFace()), stance + " turnFace");
            assertFalse(SMILES.contains(stance.detachedAfterFace()), stance + " detachedAfterFace");
        }
        assertFalse(TEARS.contains(SiblingStance.COLD.verdictFace()));
        assertFalse(TEARS.contains(SiblingStance.COLD.turnFace()));
        assertFalse(TEARS.contains(SiblingStance.COLD.detachedAfterFace()));
    }
}
