package io.github.longshou.mcyukkuri.pet.rules;

import static org.junit.jupiter.api.Assertions.*;
import io.github.longshou.mcyukkuri.pet.rules.Observation.Kind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiscoveryTest {
    private final UUID brood = UUID.randomUUID();
    private final Observation sight = new Observation();
    private final Observation.Sign sign = new Observation.Sign(brood, 1, 7, false, Kind.YOUNG);

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

    @Test void onlyAnIntelligibleDeliveredAdmissionCountsForItsWitness() {
        assertEquals("confession", Observation.words(true, true, true, false));
        assertNull(Observation.words(true, false, true, false));
        assertNull(Observation.words(false, true, true, false));
    }

    @Test void theIdentifyingCallNeedsTheSameBroodAndBiologicalMother() {
        UUID mother = UUID.randomUUID(), foster = UUID.randomUUID();
        assertTrue(Observation.identifyingCall(brood, mother, brood, mother, true, true));
        assertFalse(Observation.identifyingCall(brood, foster, brood, mother, true, true));
        assertFalse(Observation.identifyingCall(brood, mother, UUID.randomUUID(), mother, true, true));
        assertFalse(Observation.identifyingCall(brood, mother, brood, mother, true, false));
        assertFalse(Observation.identifyingCall(brood, mother, brood, mother, false, true));
    }

    @Test void aWitnessedCallCanCompleteTheDecisiveObservation() {
        for (int tick = 1; tick < 40; tick++) assertFalse(sight.seen(sign, tick));
        assertNull(Observation.words(true, true, false, false)); // denial alone
        assertNull(Observation.words(false, true, false, true)); // missed the denial
        assertTrue(sight.seen(sign, 40));
        assertEquals("lie_exposed", Observation.words(true, true, false, true));
    }
    @Test void witnessesSubjectsAndInspectionWindowsCannotPoolAndReloadStartsFresh() {
        var secondJudge = new Observation.Sign(brood, 2, 7, false, Kind.YOUNG);
        var sibling = new Observation.Sign(brood, 1, 8, false, Kind.YOUNG);
        var otherBrood = new Observation.Sign(UUID.randomUUID(), 1, 7, false, Kind.YOUNG);
        var inspection = new Observation.Sign(brood, 1, 7, true, Kind.YOUNG);
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

    @Test void seeingPregnancyDoesNotCountTowardHearingTheAdmission() {
        var pregnancy = new Observation.Sign(brood, 1, 7, false, Kind.PREGNANCY);
        var admission = new Observation.Sign(brood, 1, 7, false, Kind.ADMISSION);
        for (int t = 1; t < 40; t++) assertFalse(sight.seen(pregnancy, t));
        for (int t = 40; t < 79; t++) assertFalse(sight.seen(admission, t));
        assertTrue(sight.seen(admission, 79));
    }
}
