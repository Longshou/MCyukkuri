package io.github.longshou.mcyukkuri.pet.rules;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiscoveryTest {
    private final UUID brood = UUID.randomUUID();
    private final Observation sight = new Observation();
    private final Observation.Sign sign = new Observation.Sign(brood, 1, 7, false);

    @Test void theJudgeMustBeAwakeNearbyAndFacingTheSubject() {
        assertTrue(Observation.inView(true, 256, 1));
        assertFalse(Observation.inView(false, 4, 1));
        assertFalse(Observation.inView(true, 257, 1));
        assertFalse(Observation.inView(true, 4, -1));
    }

    @Test void onlyAnOngoingAttachedPregnancyCanExplainWhatTheJudgeSees() {
        assertTrue(Observation.pregnancy(brood, brood, true, true));
        assertFalse(Observation.pregnancy(brood, brood, false, true)); // postpartum belly
        assertFalse(Observation.pregnancy(brood, brood, true, false)); // ledger only
        assertFalse(Observation.pregnancy(brood, null, true, true)); // detached stem
        assertFalse(Observation.pregnancy(brood, UUID.randomUUID(), true, true));
    }
    @Test void bothParentAndTheirOwnLivingYoungMustBeInSight() {
        UUID mother = UUID.randomUUID(), father = UUID.randomUUID();
        assertTrue(Observation.young(brood, father, brood, mother, father, false, true));
        assertFalse(Observation.young(brood, father, brood, mother, null, false, true));
        assertFalse(Observation.young(brood, mother, UUID.randomUUID(), mother, father, false, true));
        assertFalse(Observation.young(brood, mother, brood, mother, father, true, true));
        assertFalse(Observation.young(brood, mother, brood, mother, father, false, false)); // child behind cover
    }
    @Test void fragmentedSightExpiresAtTheRollingBoundary() {
        for (int t = 1; t <= 20; t++) assertFalse(sight.seen(sign, t));
        for (int t = 182; t <= 201; t++) assertFalse(sight.seen(sign, t)); // tick 1 has expired
        for (int t = 202; t <= 220; t++) assertFalse(sight.seen(sign, t));
        assertTrue(sight.seen(sign, 221));
    }
    @Test void witnessesSubjectsAndInspectionWindowsCannotPoolAndReloadStartsFresh() {
        var secondJudge = new Observation.Sign(brood, 2, 7, false);
        var sibling = new Observation.Sign(brood, 1, 8, false);
        var otherBrood = new Observation.Sign(UUID.randomUUID(), 1, 7, false);
        var inspection = new Observation.Sign(brood, 1, 7, true);
        for (int t = 0; t < 39; t++) {
            assertFalse(sight.seen(sign, t));
            assertFalse(sight.seen(sign, t));
        }
        for (var distinct : new Observation.Sign[]{secondJudge, sibling, otherBrood, inspection})
            assertFalse(sight.seen(distinct, 60));
        assertFalse(new Observation().seen(sign, 60));
        assertTrue(sight.seen(sign, 60));
        sight.expire(260);
        assertFalse(sight.seen(sign, 260));
    }
}
