package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HullLoadTest {
    @Test void eachBodyContributesItsVolumeAndGrowthChangesTheLoad() {
        double capacity = HullBounds.capacity(1);
        assertEquals((Math.pow(.15, 3) + Math.pow(.2, 3) + Math.pow(.3, 3)) / capacity,
                HullBounds.loadRatio(1, List.of(.15, .2, .3)), 1e-12);
        assertEquals(8 * HullBounds.loadRatio(1, List.of(.2)), HullBounds.loadRatio(1, List.of(.4)), 1e-12);
        assertEquals(HullBounds.loadRatio(1, List.of(.2, .3)), HullBounds.loadRatio(2, List.of(.4, .6)), 1e-12);
        assertEquals(0, HullBounds.loadRatio(1, List.of()));
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
