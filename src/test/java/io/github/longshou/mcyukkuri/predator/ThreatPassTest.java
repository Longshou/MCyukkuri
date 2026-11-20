package io.github.longshou.mcyukkuri.predator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreatPassTest {
    @Test void pullUpClearsTheTopOfBothShortAndTallTargets() {
        assertEquals(74, ThreatPass.pullUpY(72.5));
        assertEquals(75.3, ThreatPass.pullUpY(73.8));
        assertFalse(ThreatPass.canDescend(75.3, 73.8));
        assertFalse(ThreatPass.canDescend(74, 73.8));
        assertTrue(ThreatPass.canDescend(75.5, 73.8));
    }

    @Test void territoryIsACircleInPlanView() {
        assertTrue(ThreatPass.inZone(6, -8));
        assertFalse(ThreatPass.inZone(8, 8));
    }
}
