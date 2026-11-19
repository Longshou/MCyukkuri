package io.github.longshou.mcyukkuri.dialogue;

/**
 * "Counting stops at four" (komicolle 40720 floor 11518780) describes an ordinary voice's default catalog.
 * Babies count to two and Patchouli to nine (floor 11518905). A living body supplies its own cap.
 */
public final class SpeechCount {
    public static final int ORDINARY_CAP = 3, BABY_CAP = 2, PATCHOULI_CAP = 9;
    public static final String FOOD = "count_food", STOCK = "count_stock", CHILDREN = "count_children", PEERS = "count_peers";

    public static int cap(String species, String tier) {
        if ("baby".equals(tier)) return BABY_CAP;
        return "patchouli".equals(species) ? PATCHOULI_CAP : ORDINARY_CAP;
    }

    /** "1".."cap" spelled out, or "many" once the quantity exceeds the speaker's cap; {@code null} for zero or less. */
    public static String bucket(int quantity, String species, String tier) {
        return bucket(quantity, cap(species, tier));
    }

    /** Dialogue event for saying {@code quantity} of {@code base}; {@code null} when there is nothing to count. */
    public static String event(String base, int quantity, String species, String tier) {
        return event(base, quantity, cap(species, tier));
    }

    public static String event(String base, int quantity, int cap) {
        String bucket = bucket(quantity, cap);
        return bucket == null ? null : base + "_" + bucket;
    }

    private static String bucket(int quantity, int cap) {
        if (quantity <= 0) return null;
        return quantity > cap ? "many" : Integer.toString(quantity);
    }

    private SpeechCount() {}
}
