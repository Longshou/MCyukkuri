package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Which species modifiers a body gets, by its own stable form id: the subspecies kind in snake case or the variant asset id. A species may also switch on a flag ({@link #FLAGS}). */
public final class AbilityModifier {
    /** It can set its own worn hat down on the water and use it as a boat. */
    public static final String HAT_BOAT = "hat_boat";
    /** The capability keys a species modifier may carry next to the coefficient factors. */
    public static final Set<String> FLAGS = Set.of(HAT_BOAT);
    private static final String[] KIND_KEYS = Arrays.stream(Kind.values())
            .map(kind -> kind.name().toLowerCase(Locale.ROOT)).toArray(String[]::new);

    /** The data-file key of this body's species, e.g. {@code aquatic_marisa}. */
    public static String speciesKey(YukkuriEntity body) {
        Kind kind = body.subspecies().kind().behaviorKind();
        return kind == Kind.NORMAL ? body.getVariant().assetId() : KIND_KEYS[kind.ordinal()];
    }

    /** The table's factors for this body, identity when the data authors none for its species. */
    public static AbilityMath.Multipliers of(YukkuriEntity body) {
        return AbilityTable.modifier(speciesKey(body));
    }

    /** Whether the data gives this body's species the capability {@code key} (one of {@link #FLAGS}). */
    public static boolean flag(YukkuriEntity body, String key) {
        return of(body).flag(key);
    }

    private AbilityModifier() {}
}
