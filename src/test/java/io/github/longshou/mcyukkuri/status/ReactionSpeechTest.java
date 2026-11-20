package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.mind.Ousting.Answer;
import org.junit.jupiter.api.Test;

import static io.github.longshou.mcyukkuri.status.StruckStance.*;
import static org.junit.jupiter.api.Assertions.*;

class ReactionSpeechTest {
    @Test void theChosenResponseDeterminesTheLine() {
        assertEquals("hurt_flee", ReactionSpeech.struck(FLEE, true, false, false));
        assertEquals("afraid", ReactionSpeech.struck(FLEE, false, false, false));
        assertEquals("hurt_freeze", ReactionSpeech.struck(FREEZE, true, false, false));
        assertEquals("hurt_defy", ReactionSpeech.struck(PUFF_FIGHT, true, false, true));
        assertEquals("puff", ReactionSpeech.struck(PUFF_FIGHT, true, false, false));
        assertEquals("puff", ReactionSpeech.struck(PUFF_FIGHT, false, false, true));
        assertEquals("hurt_endure", ReactionSpeech.struck(ENDURE, true, false, false));
        assertEquals("", ReactionSpeech.struck(ENDURE, false, false, false));
        assertEquals("hurt_endure", ReactionSpeech.struck(SEAL, true, false, false));
        assertEquals("", ReactionSpeech.struck(LEAD_AWAY, true, false, false));
        assertEquals("protect_taunt", ReactionSpeech.leadTaunt(true));
        assertEquals("puff", ReactionSpeech.leadTaunt(false));
    }

    @Test void treatmentLinesRequireInjuryAndATrustedKeeper() {
        for (var identity : Identity.values()) {
            boolean kept = identity.companion() || identity == Identity.OWNED || identity == Identity.STOCK;
            assertEquals(kept ? "hurt_tame_plea" : "",
                    ReactionSpeech.struck(PLEAD, true, ReactionSpeech.trustsCareFrom(identity, true), false));
            assertEquals("", ReactionSpeech.struck(PLEAD, true, ReactionSpeech.trustsCareFrom(identity, false), false));
        }
        assertEquals("", ReactionSpeech.struck(PLEAD, false, true, false));
    }

    @Test void badgeSpeechUsesTheActualSubjectAndVisibleRanks() {
        for (var event : new String[] {"badge_envy", "badge_envy_snark", "badge_admire", "badge_ask", "badge_glance", "badge_pull_back"}) {
            assertEquals("", ReactionSpeech.addressed(event, 0, 0, null));
            assertEquals("", ReactionSpeech.addressed(event, 0, 0, Identity.PET));
            assertEquals(event, ReactionSpeech.addressed(event, 0, 2, Identity.PET));
        }
        assertEquals("", ReactionSpeech.addressed("badge_envy", 2, 2, Identity.PET));
        assertEquals("", ReactionSpeech.addressed("badge_proud", 0, 2, Identity.PET));
        assertEquals("badge_proud", ReactionSpeech.addressed("badge_proud", 2, 0, null));
        assertEquals("badge_pity", ReactionSpeech.addressed("badge_pity", 0, 0, Identity.STRAY));
        assertEquals("", ReactionSpeech.addressed("badge_pity", 0, 2, Identity.PET));
        assertEquals("badge_retort", ReactionSpeech.addressed("badge_retort", 2, 0, Identity.STRAY));
        assertEquals("puff", ReactionSpeech.addressed("badge_retort", 2, 0, Identity.PET));
        assertEquals("home_declare", ReactionSpeech.addressed("home_declare", 0, 0, null));
    }

    @Test void rejectionLinesMatchTheBondAndOustingAnswer() {
        assertEquals("settlement_hurt", ReactionSpeech.contempt(true));
        assertEquals("", ReactionSpeech.contempt(false));
        assertEquals("driven_curse", ReactionSpeech.ousted(Answer.CURSE, false));
        assertEquals("child_curse_parent", ReactionSpeech.ousted(Answer.CURSE, true));
        assertEquals("driven_farewell", ReactionSpeech.ousted(Answer.FAREWELL, false));
        assertEquals("", ReactionSpeech.ousted(Answer.SHRUG, false));
    }
}
