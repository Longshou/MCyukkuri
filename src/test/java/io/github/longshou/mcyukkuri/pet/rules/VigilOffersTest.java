package io.github.longshou.mcyukkuri.pet.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VigilOffersTest {
    private static final VigilOffers.Site CARTON = new VigilOffers.Site("minecraft:overworld", 42);

    @Test void savedAnswerSurvivesReloadAndOnlyMatchesTheSameSite() {
        var before = new VigilOffers();
        before.answer(CARTON, new VigilOffers.Evidence(.1, .025));
        var reloaded = new VigilOffers();
        before.snapshot().forEach(reloaded::answer);

        var sameEvidence = new VigilOffers.Evidence(.1, .025);
        assertFalse(reloaded.fresh(new VigilOffers.Site("minecraft:overworld", 42), sameEvidence));
        assertTrue(reloaded.fresh(new VigilOffers.Site("minecraft:overworld", 43), sameEvidence));
        assertTrue(reloaded.fresh(new VigilOffers.Site("minecraft:the_nether", 42), sameEvidence));
    }

    @Test void changedPressureOrBondCanReopenTheChoice() {
        var vigil = new VigilOffers();
        vigil.answer(CARTON, new VigilOffers.Evidence(.1, .025));

        assertFalse(vigil.fresh(CARTON, new VigilOffers.Evidence(.24, .075)));
        assertTrue(vigil.fresh(CARTON, new VigilOffers.Evidence(.25, .025)));
        assertTrue(vigil.fresh(CARTON, new VigilOffers.Evidence(.1, .1)));
    }

    @Test void endingTheVigilDiscardsItsAnswers() {
        var vigil = new VigilOffers();
        var offer = new VigilOffers.Evidence(0, 0);
        vigil.answer(CARTON, offer);
        vigil.clear();

        assertTrue(vigil.snapshot().isEmpty());
        assertTrue(vigil.fresh(CARTON, offer));
    }
}
