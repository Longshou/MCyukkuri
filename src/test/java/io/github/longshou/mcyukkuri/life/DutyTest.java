package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.life.Duty.End;
import io.github.longshou.mcyukkuri.life.Duty.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DutyTest {
    @Test
    void replacementFollowsRankAndAllowsRetakingTheHeldKind() {
        for (Kind wanted : Kind.values()) assertTrue(Duty.mayReplace(null, wanted));
        assertTrue(Duty.mayReplace(Kind.WATCH, Kind.SEARCH));
        assertTrue(Duty.mayReplace(Kind.SEARCH, Kind.SEAL));
        assertTrue(Duty.mayReplace(Kind.SEAL, Kind.SEAL));
        assertFalse(Duty.mayReplace(Kind.SEAL, Kind.SEARCH));
        assertFalse(Duty.mayReplace(Kind.SEARCH, Kind.WATCH));
    }

    @Test
    void familyDutiesOutrankCertification() {
        for (Kind family : new Kind[] {Kind.WATCH, Kind.SEARCH, Kind.SEAL}) {
            assertTrue(Duty.mayReplace(Kind.EXAM, family));
            assertFalse(Duty.mayReplace(family, Kind.EXAM));
        }
    }

    @Test
    void emergencyTakesPrecedenceOverTheDeadline() {
        assertNull(Duty.lapse(99, 100, false));
        assertEquals(End.DEADLINE, Duty.lapse(100, 100, false));
        assertEquals(End.EMERGENCY, Duty.lapse(100, 100, true));
        assertEquals(End.EMERGENCY, Duty.lapse(99, 100, true));
    }

    @Test
    void cooldownDependsOnTheEnd() {
        assertEquals(40, Duty.coolUntil(End.DONE, 40, 80));
        assertEquals(40, Duty.coolUntil(End.REPLACED, 40, 80));
        assertEquals(40, Duty.coolUntil(End.YIELDED, 40, 80));
        assertEquals(120, Duty.coolUntil(End.FAILED, 40, 80));
        assertEquals(120, Duty.coolUntil(End.DEADLINE, 40, 80));
        assertEquals(40, Duty.coolUntil(End.FAILED, 40, 0));
        assertEquals(40 + Duty.EMERGENCY_SETTLE, Duty.coolUntil(End.EMERGENCY, 40, 80));
    }
}
