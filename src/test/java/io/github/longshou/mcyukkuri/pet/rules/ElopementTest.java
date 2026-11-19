package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test void completionConsumesCustodyOnceAndOnlyRegisteredAnimalsLoseStanding() {
        var book = new BadgeConduct();
        var pet = UUID.randomUUID();
        var wild = UUID.randomUUID();
        var owner = UUID.randomUUID();
        var keeper = new AtomicReference<>(owner);
        var count = new AtomicInteger();
        Runnable release = () -> { count.incrementAndGet(); keeper.set(null); };
        assertTrue(book.eloped(pet, owner.equals(keeper.get()), true, 3, release));
        assertFalse(book.eloped(pet, owner.equals(keeper.get()), true, 4, release));
        assertEquals(1, count.get());
        assertEquals(Map.of(pet, 3L), book.elopements());
        assertEquals(0, book.standing(Map.of(pet, 3)));
        assertFalse(book.disqualified(wild));
        var loaded = new BadgeConduct();
        book.elopements().forEach(loaded::loadElopement);
        assertTrue(loaded.disqualified(pet));
        var unbadged = UUID.randomUUID();
        assertFalse(loaded.eloped(unbadged, true, false, 5, release));
        assertEquals(2, count.get());
        assertFalse(loaded.disqualified(unbadged));
        assertTrue(loaded.cases(unbadged).isEmpty());
    }
}
