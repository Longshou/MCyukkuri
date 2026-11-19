package io.github.longshou.mcyukkuri.badge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BadgeConductTest {
    private final BadgeConduct registry = new BadgeConduct();
    private final BadgeConduct.Parent mother = parent(), father = parent();
    private final UUID brood = UUID.randomUUID();
    private static BadgeConduct.Parent parent() { return new BadgeConduct.Parent(UUID.randomUUID(), UUID.randomUUID()); }

    @Test void permitCoversItsLastTickAndOnlyRegisteredParentsNeedOne() {
        var wild = new BadgeConduct.Parent(father.id(), null);
        assertFalse(registry.open(brood, mother, 20, wild, -1, 20));
        assertFalse(registry.open(brood, mother, 20, father, 20, 20));
        assertTrue(registry.open(brood, mother, 20, wild, -1, 21));
        var entry = registry.cases(wild.id()).getFirst();
        assertFalse(registry.disqualified(mother.id()));
        entry.discover(new BadgeConduct.Verdict("pregnancy", 4, mother.owner()));
        assertTrue(registry.disqualified(mother.id()));
        assertFalse(registry.disqualified(wild.id()));
    }

    @Test void oneJudgmentIncludesAbsentFatherAndPreservesOtherHouseholdContributions() {
        registry.open(brood, mother, 20, father, 19, 20);
        var otherBrood = UUID.randomUUID();
        registry.open(otherBrood, mother, -1, father, -1, 20);
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
}
