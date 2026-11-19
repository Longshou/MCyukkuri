package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElopementTest {
    @Test void visitorWeighsItsPressureBoldnessAndThePetsConditionAgainstOwnNeeds() {
        assertTrue(RulesMath.lures(100, 1, 74, 80, 100, 100, 30));
        assertFalse(RulesMath.lures(40, 1, 74, 80, 100, 100, 30));
        assertFalse(RulesMath.lures(75, 0, 74, 80, 90, 90, 20));
        assertTrue(RulesMath.lures(75, 1, 74, 80, 90, 90, 20));
        assertFalse(RulesMath.lures(75, 1, 74, 80, 10, 10, 20));
        assertFalse(RulesMath.lures(100, 1, 40, 80, 100, 100, 30));
        assertFalse(RulesMath.lures(100, 1, 74, 30, 100, 100, 30));
        assertFalse(RulesMath.receptive(0, 25, 90));
        assertTrue(RulesMath.receptive(30, 100, 5));
    }

    @Test void returningBuildsThisPairsBondAndBeingChasedUndoesTwoVisits() {
        int one = RulesMath.visitsAfterReturn(0), two = RulesMath.visitsAfterReturn(one);
        assertEquals(2, two);
        assertEquals(6, RulesMath.bondChange(0, one));
        assertEquals(6, RulesMath.bondChange(one, two));
        assertEquals(0, RulesMath.visitsAfterCounter(0));
        assertEquals(0, RulesMath.visitsAfterCounter(one));
        assertEquals(0, RulesMath.visitsAfterCounter(two));
        assertEquals(1, RulesMath.visitsAfterCounter(3));
        assertEquals(-12, RulesMath.bondChange(two, RulesMath.visitsAfterCounter(two)));
        assertTrue(RulesMath.nextVisitTicks(0) < RulesMath.nextVisitTicks(1));
    }

    @Test void onlyTheOwnerActingWithSightOfEitherPartnerCountersTheVisit() {
        assertTrue(RulesMath.witnessedCounter(true, true, false));
        assertTrue(RulesMath.witnessedCounter(true, false, true));
        assertFalse(RulesMath.witnessedCounter(true, false, false));
        assertFalse(RulesMath.witnessedCounter(false, true, true));
    }

    @Test void evenTheExtremePetCompletesTwoTrystsBeforeGivingIn() {
        for (int visits = 0; visits < 2; visits++)
            assertEquals(0, RulesMath.departureChance(100, 85, 5, 0, 5, 42, 0, visits));
        assertEquals(.95865778125, RulesMath.departureChance(100, 85, 5, 0, 5, 42, 0, 2), 1e-12);
        double caredFor = RulesMath.departureChance(85, 0, 60, 60, 70, 30, 20, 2);
        assertTrue(caredFor > 0 && caredFor < .001);
        assertEquals(0, RulesMath.departureChance(70, 85, 5, 0, 5, 42, 0, 2));
        assertEquals(0, RulesMath.departureChance(100, 85, 5, 100, 5, 42, 0, 2));
        assertEquals(0, RulesMath.departureChance(100, 85, 5, 0, 5, 42, 42, 2));
        assertEquals(1, RulesMath.departureChance(100, 100, 0, 0, 0, 100, 0, 2));
    }

    @Test void resistanceVariesContinuouslyWithEveryMotive() {
        double middle = RulesMath.departureChance(85, 49, 50, 45, 50, 30, 10, 2);
        assertTrue(RulesMath.departureChance(84, 49, 50, 45, 50, 30, 10, 2) < middle);
        assertTrue(RulesMath.departureChance(85, 50, 50, 45, 50, 30, 10, 2) > middle);
        assertTrue(RulesMath.departureChance(85, 49, 51, 45, 50, 30, 10, 2) < middle);
        assertTrue(RulesMath.departureChance(85, 49, 50, 46, 50, 30, 10, 2) < middle);
        assertTrue(RulesMath.departureChance(85, 49, 50, 45, 51, 30, 10, 2) < middle);
        assertTrue(RulesMath.departureChance(85, 49, 50, 45, 50, 31, 10, 2) > middle);
        assertTrue(RulesMath.departureChance(85, 49, 50, 45, 50, 30, 11, 2) < middle);
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
