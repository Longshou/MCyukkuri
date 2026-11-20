package io.github.longshou.mcyukkuri.shop;

import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.shop.DockBedding.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class DockBeddingTest {
    private static final DockBedding.Slot EMPTY_SLOT = new DockBedding.Slot(EMPTY, 0);
    private static final DockBedding.Slot BASKET = new DockBedding.Slot(CARRIER, 1);
    private static final DockBedding.Slot GOODS = new DockBedding.Slot(OTHER, 12);
    private static DockBedding.Slot cushions(int count) { return new DockBedding.Slot(CUSHION, count); }
    private static DockBedding.Slot woven() { return new DockBedding.Slot(WOVEN, 1); }

    @Test void roundTripThroughEveryLayoutKeepsThreeCushionsAndOneWovenBed() {
        var dock = List.of(cushions(2), woven(), BASKET, GOODS, EMPTY_SLOT);
        var large = DockBedding.exchange(dock, 1, -1, 64, 64, 2);
        assertEquals(List.of(cushions(3), EMPTY_SLOT, BASKET, GOODS, EMPTY_SLOT), large);
        var split = DockBedding.exchange(large, -3, 1, 64, 64, 3);
        assertEquals(List.of(woven(), EMPTY_SLOT, BASKET, GOODS, EMPTY_SLOT), split);
        var single = DockBedding.exchange(split, 2, 0, 64, 64, 2);
        assertEquals(List.of(woven(), cushions(2), BASKET, GOODS, EMPTY_SLOT), single);
        assertEquals(single, DockBedding.exchange(single, 0, 0, 64, 64, 0));
    }

    @Test void missingStockAndAFullDockLeaveCargoIntact() {
        var dock = List.of(cushions(1), BASKET, GOODS);
        assertTrue(DockBedding.exchange(dock, 1, -1, 64, 64, 0).isEmpty());
        assertTrue(DockBedding.exchange(dock, -2, 0, 64, 64, 0).isEmpty());
        assertTrue(DockBedding.exchange(dock, 0, 1, 64, 64, 0).isEmpty());
        assertEquals(List.of(cushions(1), BASKET, GOODS), dock);
        var oneFree = List.of(GOODS, BASKET, EMPTY_SLOT);
        assertTrue(DockBedding.exchange(oneFree, 1, 0, 64, 64, 2).isEmpty());
    }

    @Test void withdrawingTheLastCushionInAStackMakesRoomForTheWovenReturn() {
        assertEquals(List.of(cushions(4), woven(), GOODS),
                DockBedding.exchange(List.of(cushions(4), cushions(1), GOODS), -1, 1, 64, 64, 0));
    }
}
