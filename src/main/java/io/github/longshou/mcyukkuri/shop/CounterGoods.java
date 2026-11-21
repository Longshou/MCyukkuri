package io.github.longshou.mcyukkuri.shop;

import java.util.Map;

/**
 * The one goods list of a staffed counter (corner shop, market stall): what it sells for one emerald each and what it buys back for
 * one emerald per batch, plus the row arithmetic of the crates that hold it. Plain ids and counts, no Minecraft types, so the
 * rules are unit tested.
 */
public final class CounterGoods {
    /** Items the buyer gets for one emerald. */
    public static final int RETAIL = 1;
    /**
     * Items the counter takes for one emerald. Every batch is larger than {@link #RETAIL}, so buying and selling the same good back
     * always loses emeralds and the till only ever pays out what it was given or what players paid in. Finished goods take 2, apples
     * 3, raw produce and paper 4.
     */
    private static final Map<String, Integer> BATCH = Map.ofEntries(
            Map.entry("minecraft:bread", 2), Map.entry("mcyukkuri:sweet_candy", 2), Map.entry("mcyukkuri:oil_grain_regular", 2),
            Map.entry("mcyukkuri:oil_grain_budget", 2), Map.entry("minecraft:pumpkin_pie", 2), Map.entry("minecraft:apple", 3),
            Map.entry("minecraft:paper", 4), Map.entry("minecraft:carrot", 4), Map.entry("minecraft:potato", 4),
            Map.entry("minecraft:beetroot", 4), Map.entry("minecraft:candle", 4));

    private CounterGoods() {}

    public static boolean sells(String id) { return BATCH.containsKey(id); }

    /** The buy-back batch of a good, 0 when the counter does not handle it. */
    public static int batch(String id) { return BATCH.getOrDefault(id, 0); }

    /**
     * Places free for one kind of item across the rows of a run: an empty row holds {@code perRow}, a row of the same kind the rest of
     * its capacity, a row of another kind nothing. {@code counts[i]} is the row's item count, {@code same[i]} whether it holds the kind.
     */
    public static int room(int[] counts, boolean[] same, int perRow) {
        int room = 0;
        for (int row = 0; row < counts.length; row++) {
            if (counts[row] == 0) room += perRow;
            else if (same[row]) room += Math.max(0, perRow - counts[row]);
        }
        return room;
    }
}
