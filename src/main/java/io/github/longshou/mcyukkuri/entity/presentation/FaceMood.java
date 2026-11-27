package io.github.longshou.mcyukkuri.entity.presentation;

import java.util.List;
import java.util.Map;

/**
 * The valence of a face id, and the faces it calms through. Pure data: no entity types. A calming face never leaves the mood of the
 * face it follows (a cry never calms into a smile), so a hold that ends cannot show a mixed "cry eyes, smiling mouth" look.
 */
public enum FaceMood {
    SAD, ANGRY, FEAR, JOY,
    /** The one authored mixed face (teary_smile): a leaver's farewell, a vow, a grief closing. It has no calming path. */
    BITTERSWEET,
    NEUTRAL;

    private static final Map<String, FaceMood> OF = Map.ofEntries(
            Map.entry("cry", SAD), Map.entry("teary", SAD), Map.entry("side_teary", SAD), Map.entry("puff_teary", SAD),
            Map.entry("teary_frown", SAD), Map.entry("open_cry", SAD), Map.entry("pin_cry", SAD), Map.entry("pressed_cry", SAD),
            Map.entry("pressed_teary", SAD), Map.entry("tongue_cry", SAD), Map.entry("blank_cry", SAD), Map.entry("impact_teary", SAD),
            Map.entry("angry", ANGRY), Map.entry("angry_stare", ANGRY), Map.entry("fierce", ANGRY), Map.entry("cheek_puff", ANGRY), Map.entry("puff", ANGRY),
            Map.entry("puff_open", ANGRY), Map.entry("down_angry", ANGRY), Map.entry("hmph", ANGRY),
            Map.entry("horrified", FEAR), Map.entry("afraid", FEAR), Map.entry("surprise", FEAR), Map.entry("confused_small", FEAR),
            Map.entry("happy", JOY), Map.entry("open_smile", JOY), Map.entry("closed_smile", JOY), Map.entry("satisfied", JOY),
            Map.entry("blank_happy", JOY), Map.entry("sparkle", JOY),
            Map.entry("teary_smile", BITTERSWEET));

    public static FaceMood of(String face) { return OF.getOrDefault(face, NEUTRAL); }

    /**
     * The faces a hold calms through after it ends, in order (empty: none; mild faces have no calmer one). Anger passes through a
     * lowered brow and, after a LONG hold, a sulk. Every id is in every model's selectable set (unit-tested).
     */
    public static List<String> calming(String face, FaceTier.Tier tier) {
        return switch (face) {
            case "cry", "teary", "puff_teary", "open_cry", "pin_cry", "pressed_cry", "pressed_teary", "tongue_cry" -> List.of("side_teary");
            case "angry", "angry_stare", "fierce", "cheek_puff", "puff", "puff_open" ->
                    tier == FaceTier.Tier.LONG ? List.of("down_angry", "hmph") : List.of("down_angry");
            case "horrified", "afraid", "surprise" -> List.of("confused_small");
            case "happy", "open_smile" -> List.of("closed_smile");
            default -> List.of();
        };
    }
}
