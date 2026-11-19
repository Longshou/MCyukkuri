package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.status.Identity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElopementTest {
    @Test void bothMustPreferTheMatchAndOnlyWildPartnersQualify() {
        for (Identity partner : Identity.values())
            assertEquals(partner.wild(), RulesMath.drawnTo(partner, 12, 12, 9, 0));
        var wild = Identity.WILD_FAMILY;
        assertFalse(RulesMath.drawnTo(wild, 11, 30, 0, 80));
        assertFalse(RulesMath.drawnTo(wild, 30, 11, 0, 80));
        assertFalse(RulesMath.drawnTo(wild, 30, 30, 30, 80));
        assertFalse(RulesMath.drawnTo(wild, 30, 30, 20, 49));
        assertTrue(RulesMath.drawnTo(wild, 30, 30, 20, 50));
        assertEquals(0, RulesMath.departureChance(69, 0));
        assertEquals(.25, RulesMath.departureChance(70, 0));
        assertEquals(.125, RulesMath.departureChance(90, 50));
        assertEquals(0, RulesMath.departureChance(100, 100));
    }

    @Test void interventionSeparationDangerAndDependantsInterrupt() {
        assertEquals("owner_intervened", RulesMath.interrupted(true, true, true, false));
        assertEquals("partner_lost", RulesMath.interrupted(false, false, true, false));
        assertEquals("unable_to_travel", RulesMath.interrupted(false, true, false, false));
        assertEquals("family_care", RulesMath.interrupted(false, true, true, true));
        assertNull(RulesMath.interrupted(false, true, true, false));
    }

    @Test void onlyBothPhysicalArrivalsOutsideTheYardCount() {
        assertFalse(RulesMath.departed(false, true, 625, 729, 4));
        assertFalse(RulesMath.departed(true, false, 625, 729, 4));
        assertFalse(RulesMath.departed(true, true, 64, 64, 4));
        assertFalse(RulesMath.departed(true, true, 625, 576, 4));
        assertFalse(RulesMath.departed(true, true, 625, 729, 25));
        assertTrue(RulesMath.departed(true, true, 625, 729, 4));
    }
}
