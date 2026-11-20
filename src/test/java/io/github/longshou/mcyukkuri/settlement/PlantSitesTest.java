package io.github.longshou.mcyukkuri.settlement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlantSitesTest {
    private record Tuft(double x, double z, double distance) {}

    @Test void activeSightingExcludesTheCloserPlantForEitherHarvest() {
        var near = new Tuft(8, 6, 1);
        var safe = new Tuft(11, 0, 4);
        var candidates = List.of(safe, near);
        assertEquals(List.of(safe), PlantSites.nearest(candidates, Tuft::distance,
                tuft -> !PlantSites.threatened(1, tuft.x, tuft.z)).toList());
        assertEquals(List.of(near, safe), PlantSites.nearest(candidates, Tuft::distance,
                tuft -> !PlantSites.threatened(0, tuft.x, tuft.z)).toList());
    }

    @Test void threatBoundaryIncludesTenBlocksOnlyWhileRemembered() {
        assertTrue(PlantSites.threatened(1, -6, 8));
        assertFalse(PlantSites.threatened(1, -6, 9));
        assertFalse(PlantSites.threatened(0, 0, 0));
    }
}
