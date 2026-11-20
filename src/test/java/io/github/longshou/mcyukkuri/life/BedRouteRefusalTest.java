package io.github.longshou.mcyukkuri.life;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BedRouteRefusalTest {
    @Test void floorSleeperSurveysItsRouteFromTheSameCellUntilTheDoorAndWalkAreClear() {
        var failed = new BedRouteRefusal(1, 2, 100);
        var blocked = new HashSet<>(List.of("doorway", "aisle"));
        assertFalse(failed.permits(1, 2, 119, () -> fail("Resurveyed the refused walk immediately")));
        blocked.remove("doorway");
        assertFalse(failed.permits(1, 2, 120, blocked::isEmpty));
        blocked.remove("aisle");
        assertTrue(failed.permits(1, 2, 140, blocked::isEmpty));
        assertTrue(failed.permits(1, 2, 141, () -> fail("Repeated the same survey")));
    }

    @Test void anotherBedOrStartingCellCanUseItsOwnApproach() {
        var failed = new BedRouteRefusal(1, 2, 0);
        assertTrue(failed.permits(3, 2, 1, () -> fail()));
        assertTrue(failed.permits(1, 3, 1, () -> fail()));
    }
}
