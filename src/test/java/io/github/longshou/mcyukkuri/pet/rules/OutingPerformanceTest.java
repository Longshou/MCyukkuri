package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.pet.social.BadgeArcPolicy.Arc;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class OutingPerformanceTest {
    @Test void optionalBadgeArcsWaitForLeadRelease() {
        var performances = EnumSet.of(Arc.GLANCE, Arc.ENVY, Arc.BOAST, Arc.PLEA, Arc.PITY, Arc.ELDER_WARN);
        for (var arc : Arc.values()) {
            assertEquals(!performances.contains(arc), OutingPerformance.allowed(true, arc.optionalPerformance()), arc.id);
            assertTrue(OutingPerformance.allowed(false, arc.optionalPerformance()), arc.id);
        }
    }

    @Test void leadDisplacesLeisureWithoutBlockingOtherPriorities() {
        assertFalse(OutingPerformance.allowed(true, true));
        assertTrue(OutingPerformance.allowed(true, false));
        assertTrue(OutingPerformance.allowed(false, true));
    }
}
