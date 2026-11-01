package io.github.longshou.mcyukkuri.aquatic;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HullLarderTest {
    @Test void acceptsOnlyAvailableCapacityAcrossKinds() {
        var stores = new HullLarder.Counts();
        assertEquals(3, stores.add("minecraft:apple", 3));
        assertEquals(5, stores.add("minecraft:bread", 8));
        assertEquals(8, stores.total());
        assertEquals(0, stores.add("minecraft:carrot", 1));
        assertEquals(Map.of("minecraft:apple", 3, "minecraft:bread", 5), stores.save());
    }

    @Test void takingLastOfAKindRemovesItAndMakesRoom() {
        var stores = new HullLarder.Counts();
        stores.add("minecraft:apple", 1);
        stores.add("minecraft:bread", 7);
        stores.take("minecraft:apple");
        assertEquals(Map.of("minecraft:bread", 7), stores.save());
        assertEquals(1, stores.add("minecraft:carrot", 8));
        stores.take("minecraft:bread");
        assertEquals(6, stores.count("minecraft:bread"));
        assertEquals(7, stores.total());
    }

    @Test void everyCountUsesTheSpecifiedTierAndCoverage() {
        int[] tiers = {0, 1, 1, 2, 2, 2, 3, 3, 3};
        double[] radii = {0, .45, .45, .70, .70, .70, 1, 1, 1};
        for (int count = 0; count <= 8; count++) {
            assertEquals(tiers[count], HullLarder.Counts.tier(count));
            assertEquals(radii[count], HullLarder.Counts.radius(count));
        }
    }

    @Test void spillConservesEachKindAfterEatingAndEmptiesTheHold() {
        var stores = new HullLarder.Counts();
        stores.add("minecraft:apple", 3);
        stores.add("minecraft:bread", 5);
        stores.take("minecraft:bread");
        assertEquals(Map.of("minecraft:apple", 3, "minecraft:bread", 4), stores.spill());
        assertEquals(0, stores.total());
        assertEquals(0, HullLarder.Counts.tier(stores.total()));
        assertTrue(stores.spill().isEmpty());
    }

    @Test void savedCountMapRoundTripsWithoutSharingMutableState() {
        var stores = new HullLarder.Counts();
        stores.add("minecraft:apple", 2);
        stores.add("minecraft:bread", 4);
        var saved = stores.save();
        stores.take("minecraft:apple");
        var loaded = new HullLarder.Counts();
        loaded.load(saved);
        assertEquals(Map.of("minecraft:apple", 2, "minecraft:bread", 4), loaded.save());
        assertEquals(6, loaded.total());
        saved.clear();
        assertEquals(2, loaded.count("minecraft:apple"));
        loaded.load(Map.of());
        assertEquals(0, loaded.total());
    }
}
