package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import java.util.Locale;

/**
 * Which species modifiers a body gets (spec 8.1/8.2, Q2). The key is the body's own stable form id: the subspecies kind
 * in lower snake case ({@code aquatic_marisa}), or the variant asset id ({@code marisa}, {@code house_marisa}) for the
 * ordinary form. Both are read through {@code YukkuriEntity}'s own accessors, so {@code motor/} never imports
 * {@code subspecies/}.
 */
public final class AbilityModifier {
    /** The data-file key of this body's species, e.g. {@code aquatic_marisa}. */
    public static String speciesKey(YukkuriEntity body) {
        String kind = body.subspecies().kind().name().toLowerCase(Locale.ROOT);
        return "normal".equals(kind) ? body.getVariant().assetId() : kind;
    }

    /** The table's factors for this body, identity when the data authors none for its species. */
    public static AbilityMath.Multipliers of(YukkuriEntity body) {
        return AbilityTable.modifier(speciesKey(body));
    }

    private AbilityModifier() {}
}
