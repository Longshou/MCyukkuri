package io.github.longshou.mcyukkuri.badge;

import static org.junit.jupiter.api.Assertions.*;
import io.github.longshou.mcyukkuri.pet.rules.ArcStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BadgeConductTest {
    private final BadgeConduct registry = new BadgeConduct();
    private final BadgeConduct.Parent mother = parent(), father = parent();
    private final UUID brood = UUID.randomUUID();
    private static BadgeConduct.Parent parent() { return new BadgeConduct.Parent(UUID.randomUUID(), UUID.randomUUID(), ArcStyle.HIDE); }

    @Test void aPaidArrangementUsesTheFamilyTransactionWithoutOpeningAVoluntaryCase() throws IOException {
        // Check the compiled call-site boundary without loading Minecraft or substituting a mock shop.
        for (String path : List.of("family/Courtship", "house/HouseNestBlockEntity", "shop/ShopDate", "family/FamilyData")) {
            try (var file = getClass().getResourceAsStream("/io/github/longshou/mcyukkuri/" + path + ".class")) {
                var symbols = new String(file.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertTrue(symbols.contains("conceive"), path);
                assertEquals(path.equals("family/Courtship") || path.equals("house/HouseNestBlockEntity"),
                        symbols.contains("voluntaryBrood"), path);
            }
        }
    }

    @Test void permitCoversItsLastTickAndOnlyRegisteredParentsNeedOne() {
        var wild = new BadgeConduct.Parent(father.id(), null, null);
        assertFalse(registry.open(brood, mother, 20, wild, -1, 20, false));
        assertFalse(registry.open(brood, mother, 20, father, 20, 20, false));
        assertTrue(registry.open(brood, mother, 20, wild, -1, 21, false));
        var entry = registry.cases(wild.id()).getFirst();
        assertFalse(registry.disqualified(mother.id()));
        entry.discover(new BadgeConduct.Verdict("pregnancy", 4, mother.owner()));
        assertTrue(registry.disqualified(mother.id()));
        assertFalse(registry.disqualified(wild.id()));
    }

    @Test void oneJudgmentIncludesAbsentFatherAndPreservesOtherHouseholdContributions() {
        registry.open(brood, mother, 20, father, 19, 20, false);
        var otherBrood = UUID.randomUUID();
        registry.open(otherBrood, mother, -1, father, -1, 20, false);
        var cases = registry.cases(mother.id());
        assertEquals(2, cases.size());
        var credits = Map.of(father.id(), 3, UUID.randomUUID(), 2);
        assertEquals(3, registry.standing(credits));
        var entry = cases.getFirst();
        assertTrue(entry.discover(new BadgeConduct.Verdict("exam", 5, UUID.randomUUID())));
        assertFalse(entry.discover(new BadgeConduct.Verdict("babies", 6, father.owner())));
        assertEquals("exam", entry.verdict().cause());
        assertTrue(registry.disqualified(father.id()));
        assertEquals(2, registry.standing(credits));
        assertEquals(father.owner(), entry.parents.getLast().owner());
        assertNotEquals(mother.owner(), father.owner());
        registry.remove(cases.getLast());
        assertEquals(1, cases.size());
        assertTrue(registry.disqualified(mother.id()));
        assertFalse(registry.disqualified(otherBrood));
    }

    @Test void anUnseenBroodKeepsBothGradesUntilItsEvidenceIsGone() {
        assertTrue(registry.open(brood, mother, -1, father, -1, 1, false));
        var motherCredits = Map.of(mother.id(), 2);
        var fatherCredits = Map.of(father.id(), 1);
        var entry = registry.cases(father.id()).getFirst();
        assertNull(entry.verdict());
        assertEquals(2, registry.standing(motherCredits));
        assertEquals(1, registry.standing(fatherCredits));
        registry.remove(entry);
        assertTrue(registry.cases(mother.id()).isEmpty());
        assertTrue(registry.cases(father.id()).isEmpty());
        assertEquals(2, registry.standing(motherCredits));
        assertEquals(1, registry.standing(fatherCredits));
    }

    @Test void qualifiedSightFromEitherParentWaitsAndEachSpokenCauseCanWinOnce() {
        for (String cause : List.of("lie_exposed", "confession", "naive_introduction")) {
            var entry = new BadgeConduct.Case(brood, mother, father, null);
            var sight = evidence("babies", father);
            var spoken = evidence(cause, mother);
            entry.exchange(mother.id(), 100);
            assertNull(entry.sight(sight, 40));
            assertNull(entry.verdict());
            assertEquals(spoken, entry.spoken(spoken, 41));
            assertEquals(spoken.verdict(), entry.verdict());
            assertNull(entry.spoken(spoken, 42));
            assertNull(entry.sight(sight, 42));
            assertNull(entry.abandonExchange(mother.id(), 43));
        }
    }

    @Test void theLastAbandonmentReleasesTheFirstQualifiedSightWithItsOriginalWitness() {
        var entry = new BadgeConduct.Case(brood, mother, father, null);
        var first = evidence("pregnancy", father);
        entry.exchange(mother.id(), 100);
        entry.exchange(father.id(), 120);
        assertNull(entry.sight(first, 40));
        assertNull(entry.sight(evidence("babies", mother), 41));
        assertNull(entry.abandonExchange(mother.id(), 50));
        assertNull(entry.expireExchanges(119));
        assertEquals(first, entry.expireExchanges(120));
        assertEquals(first.verdict(), entry.verdict());
        assertNull(entry.expireExchanges(121));
    }

    @Test void abandonmentWithNoEvidenceLeavesTheOrdinarySightRuleIntact() {
        var entry = new BadgeConduct.Case(brood, mother, father, null);
        entry.exchange(mother.id(), 100);
        assertNull(entry.abandonExchange(mother.id(), 30));
        assertNull(entry.verdict());
        assertNull(entry.spoken(evidence("lie_exposed", mother), 35));
        var sight = evidence("babies", father);
        assertEquals(sight, entry.sight(sight, 40));
    }

    @Test void aSpokenProofAtTheDeadlineCannotReplaceHeldSight() {
        var entry = new BadgeConduct.Case(brood, mother, father, null);
        var sight = evidence("babies", father);
        entry.exchange(mother.id(), 100);
        assertNull(entry.sight(sight, 40));
        assertEquals(sight, entry.spoken(evidence("lie_exposed", mother), 100));
    }

    private static BadgeConduct.Evidence evidence(String cause, BadgeConduct.Parent witnessParent) {
        return new BadgeConduct.Evidence(new BadgeConduct.Verdict(cause, 4, witnessParent.owner()),
                witnessParent.id(), UUID.randomUUID(), cause);
    }
}
