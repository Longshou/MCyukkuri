package io.github.longshou.mcyukkuri.dialogue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutually exclusive verbal tics (komicolle 40720 floor 11518905: Myon owns either "咪呦" or "金寶", never both).
 * A species with quirk options is listed in {@link #OPTIONS}; each individual owns exactly one option, derived
 * from its UUID so it is stable for life without needing a saved field. When the Myon entity lands, it can
 * either keep this derivation or store the choice at generation and pass it to
 * {@link NativeDialogue#select(String, String, String, String, net.minecraft.util.RandomSource)}.
 * Catalog lines carry the option in their {@code quirk} field, and a quirk-bearing speaker only ever selects
 * lines of its own option, so the two tics cannot mix.
 */
public final class SpeechQuirk {
    private static final Map<String, List<String>> OPTIONS = Map.of("myon", List.of("miyo", "kinbao"));

    /** The quirk of this individual, or {@code null} when the species has none. */
    public static String of(String species, UUID id) {
        List<String> options = OPTIONS.get(species);
        if (options == null) return null;
        return options.get(Math.floorMod(Long.hashCode(id.getMostSignificantBits() ^ id.getLeastSignificantBits()), options.size()));
    }

    public static boolean has(String species) { return OPTIONS.containsKey(species); }
    private SpeechQuirk() {}
}
