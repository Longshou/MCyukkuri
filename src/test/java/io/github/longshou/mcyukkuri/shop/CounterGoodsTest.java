package io.github.longshou.mcyukkuri.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CounterGoodsTest {
    private static final List<String> GOODS = List.of("minecraft:bread", "mcyukkuri:sweet_candy", "mcyukkuri:oil_grain_regular",
            "mcyukkuri:oil_grain_budget", "minecraft:pumpkin_pie", "minecraft:apple", "minecraft:paper", "minecraft:carrot",
            "minecraft:potato", "minecraft:beetroot", "minecraft:candle");

    @Test void everyBatchIsWithinTwoToFourAndBeatsTheRetailPrice() {
        for (var id : GOODS) {
            assertTrue(CounterGoods.sells(id), id);
            assertTrue(CounterGoods.batch(id) >= 2 && CounterGoods.batch(id) <= 4, id);
            // Buying one back costs RETAIL emeralds per item; selling pays 1 emerald per batch: a round trip always loses.
            assertTrue(CounterGoods.batch(id) * CounterGoods.RETAIL > 1, id);
        }
    }

    @Test void whatTheCounterDoesNotHandleHasNoBatch() {
        assertFalse(CounterGoods.sells("minecraft:emerald"));
        assertFalse(CounterGoods.sells("minecraft:diamond"));
        assertEquals(0, CounterGoods.batch("minecraft:emerald"));
    }

    @Test void roomCountsEmptyRowsAndTheSpareOfRowsOfTheSameKind() {
        // rows: 3 of the kind, empty, 4 of another kind, 4 of the kind
        assertEquals(1 + 4, CounterGoods.room(new int[]{3, 0, 4, 4}, new boolean[]{true, false, false, true}, 4));
        assertEquals(0, CounterGoods.room(new int[]{4, 4}, new boolean[]{false, true}, 4));
    }

    @Test void aRunOfFullCratesHasNoRoomUntilARowIsWithdrawn() {
        int[] counts = {4, 4, 4, 1};
        boolean[] same = {true, true, true, false};
        assertEquals(0, CounterGoods.room(counts, same, 4));
        counts[3] = 0; // the one emerald in the last row is paid out: the row is free for goods
        assertEquals(4, CounterGoods.room(counts, same, 4));
    }
}
