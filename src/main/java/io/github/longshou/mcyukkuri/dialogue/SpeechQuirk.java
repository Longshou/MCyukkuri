package io.github.longshou.mcyukkuri.dialogue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutually exclusive verbal tics (komicolle 40720 floor 11518905: Myon owns either "咪呦" or "金寶", never both).
 * A species with tic options is listed in {@link #OPTIONS}; each individual owns exactly one option, chosen once when it
 * is generated and saved with it ({@code rare/MyonLife}, NBT {@code Myon.SpeechQuirk}). Catalog lines carry the option in
 * their {@code quirk} field, and a tic-bearing speaker only ever selects lines of its own option, so the two cannot mix.
 */
public final class SpeechQuirk {
    private static final Map<String, List<String>> OPTIONS = Map.of("myon", List.of("miyo", "kinbao"));

    /** Legacy fallback for an individual seen before its tic was saved: the old UUID derivation. */
    public static String of(String species, UUID id) {
        List<String> options = OPTIONS.get(species);
        if (options == null) return null;
        return options.get(Math.floorMod(Long.hashCode(id.getMostSignificantBits() ^ id.getLeastSignificantBits()), options.size()));
    }
    /** A fresh individual's tic, drawn once and then saved. */
    public static String choose(String species, net.minecraft.util.RandomSource random) {
        List<String> options = OPTIONS.get(species);
        return options == null ? null : options.get(random.nextInt(options.size()));
    }
    public static boolean valid(String species, String quirk) {
        List<String> options = OPTIONS.get(species);
        return options != null && options.contains(quirk);
    }
    public static List<String> options(String species) { return OPTIONS.getOrDefault(species, List.of()); }

    public static boolean has(String species) { return OPTIONS.containsKey(species); }
    private SpeechQuirk() {}
}
