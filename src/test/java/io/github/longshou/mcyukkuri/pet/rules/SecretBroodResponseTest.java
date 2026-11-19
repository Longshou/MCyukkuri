package io.github.longshou.mcyukkuri.pet.rules;

import static org.junit.jupiter.api.Assertions.*;
import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretBroodResponseTest {
    @Test void overlappingTraitsResolveInOrderAtTheirBoundaries() {
        assertEquals(ArcStyle.NAIVE, choose(43, 10, 90, 80, 39));
        assertEquals(ArcStyle.DEFIANT, choose(44, 10, 90, 80, 39));
        assertEquals(ArcStyle.CONFESS, choose(56, 10, 90, 56, 20));
        assertEquals(ArcStyle.CONFESS, choose(70, 10, 55, 30, 60));
        assertEquals(ArcStyle.DEFIANT, choose(70, 10, 90, 30, 39));
        assertEquals(ArcStyle.LIE, choose(70, 10, 90, 30, 40));
        assertEquals(ArcStyle.HIDE, choose(55, 75, 55, 60, 80));
    }

    private ArcStyle choose(int intelligence, int friendliness, int boldness, int attachment, int obedience) {
        return ArcStyle.choose(new Temperament(intelligence, friendliness, boldness, attachment, 30), obedience);
    }

    @Test void aSecondBroodKeepsItsOwnResponseAndUnbadgedParentsKeepTheirStanding() {
        var registry = new BadgeConduct();
        var mother = UUID.randomUUID();
        var father = new BadgeConduct.Parent(UUID.randomUUID(), null, null);
        for (var style : new ArcStyle[]{ArcStyle.LIE, ArcStyle.CONFESS})
            assertTrue(registry.open(UUID.randomUUID(), new BadgeConduct.Parent(mother, null, style), -1, father, -1, 20, true));
        var cases = registry.cases(mother);
        assertEquals(ArcStyle.LIE, cases.getFirst().parents.getFirst().style());
        assertEquals(ArcStyle.CONFESS, cases.getLast().parents.getFirst().style());
        cases.getFirst().discover(new BadgeConduct.Verdict("lie_exposed", 1, UUID.randomUUID()));
        assertFalse(registry.disqualified(mother));
        assertNull(cases.getLast().verdict());
    }
}
