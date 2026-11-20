package io.github.longshou.mcyukkuri.life;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedClaimDriveTest {
    @Test
    void restedAdultPrefersItsSocialOpportunityToADistantBed() {
        assertFalse(BedClaimDrive.prefersBed(95, 95, true, false, 9));
        assertTrue(BedClaimDrive.prefersBed(95, 95, true, false, 1));
    }

    @Test
    void fatigueAndBoldnessIncreaseBedInterest() {
        assertFalse(BedClaimDrive.prefersBed(60, 12, true, false, 4.5 * 4.5));
        assertTrue(BedClaimDrive.prefersBed(60, 95, true, false, 4.5 * 4.5));
        assertTrue(BedClaimDrive.prefersBed(50, 12, true, false, 4.5 * 4.5));
    }

    @Test
    void shelterAndAGapBetweenSocialOpportunitiesAllowDistantClaims() {
        assertFalse(BedClaimDrive.prefersBed(95, 12, true, false, 36));
        assertTrue(BedClaimDrive.prefersBed(95, 12, true, true, 36));
        assertTrue(BedClaimDrive.prefersBed(95, 12, false, false, 36));
    }
}
