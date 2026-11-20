package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HullLoadTest {
    @Test void aFullHullReopensAdmissionOnlyWhenTheYoungMayFit() {
        var hull = UUID.randomUUID();
        var refused = new BoardingLoad(hull, .8, .3, .85);
        assertTrue(refused.stillFull(refused));
        assertTrue(refused.stillFull(new BoardingLoad(hull, .82, .31, .85)));
        assertFalse(refused.stillFull(new BoardingLoad(hull, .4, .3, .85)));
        assertFalse(refused.stillFull(new BoardingLoad(hull, .8, .2, .85)));
        assertFalse(refused.stillFull(new BoardingLoad(hull, .8, .3, Double.POSITIVE_INFINITY)));
        assertFalse(refused.stillFull(new BoardingLoad(UUID.randomUUID(), .8, .3, .85)));
    }

    @Test void eachBodyContributesItsVolumeAndGrowthChangesTheLoad() {
        double capacity = HullBounds.capacity(1);
        assertEquals((Math.pow(.15, 3) + Math.pow(.2, 3) + Math.pow(.3, 3)) / capacity,
                HullBounds.loadRatio(1, List.of(.15, .2, .3)), 1e-12);
        assertEquals(8 * HullBounds.loadRatio(1, List.of(.2)), HullBounds.loadRatio(1, List.of(.4)), 1e-12);
        assertEquals(HullBounds.loadRatio(1, List.of(.2, .3)), HullBounds.loadRatio(2, List.of(.4, .6)), 1e-12);
        assertEquals(0, HullBounds.loadRatio(1, List.of()));
    }

    @Test void aCarerOnlyPutsItsYoungDownWhileTheHullStillCarriesIt() {
        var family = List.of(.19, .19, .30, .28);
        double capacity = HullBounds.capacity(1);
        double load = HullBounds.loadRatio(1, family) * capacity;
        assertTrue(load <= capacity);
        assertFalse(HullBounds.carries(1, family, .19));
        assertEquals((load + HullBounds.weight(.19)) / capacity, HullBounds.loadRatioWith(1, family, .19), 1e-12);
        assertTrue(HullBounds.carries(1, List.of(.19), .19));
        assertTrue(HullBounds.carries(2, family, .19));
        assertTrue(HullBounds.carries(1, List.of(), .36));
        assertFalse(HullBounds.carries(1, List.of(.36), .30));
    }

    @Test void onlyExcessLoadRisksCapsizeAndTheChanceRisesWithoutBecomingCertain() {
        for (double ratio : new double[]{0, .85, 1}) assertEquals(0, HullTriggers.overloadChance(ratio));
        assertEquals(.2, HullTriggers.overloadChance(1.25), 1e-12);
        assertEquals(.5, HullTriggers.overloadChance(2), 1e-12);
        double previous = 0;
        for (double ratio : new double[]{1.001, 1.1, 1.5, 3, 100}) {
            double chance = HullTriggers.overloadChance(ratio);
            assertTrue(chance > previous && chance < 1);
            previous = chance;
        }
    }
}
