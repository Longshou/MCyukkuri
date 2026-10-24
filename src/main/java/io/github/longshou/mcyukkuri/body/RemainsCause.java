package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

/**
 * The cause category a death leaves on its remains snapshot (project design). It is decided once at death from the real
 * killing damage and, when that damage is only a generic carrier, from the internal cause that ended the body (old age,
 * terminal mould, poison, the non-yukkuri syndrome, an offering ...). The category picks the remains form; the original
 * {@code Cause} damage ID is kept unchanged beside it.
 */
public final class RemainsCause {
    public static final String CATEGORY = "CauseCategory", INTERNAL = "InternalCause";
    public static final String NATURAL = "natural", STARVED = "starved", BURNED = "burned", CRUSHED = "crushed", VIOLENT = "violent",
            DROWNED = "drowned", FROZEN = "frozen", SICK = "sick", UNKNOWN = "unknown";
    private RemainsCause() {}

    /** Server side, at the death snapshot. {@code internal} is the body's own cause string, empty when none. */
    public static String category(DamageSource source, String internal, boolean stemDepletion, boolean sukkiriExhaustion) {
        if (stemDepletion) return STARVED;
        if (source != null && !generic(source)) {
            if (source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_LIGHTNING)) return BURNED;
            if (source.is(DamageTypes.STARVE) || source.is(DamageTypes.DRY_OUT)) return STARVED;
            if (source.is(DamageTypeTags.IS_DROWNING) || YukkuriLife.isSoaking(source)) return DROWNED;
            if (source.is(DamageTypeTags.IS_FREEZING)) return FROZEN;
            if (InjuryModel.crush(source) || source.is(DamageTypes.FALLING_STALACTITE) || source.is(DamageTypeTags.IS_FALL)
                    || source.is(DamageTypes.CRAMMING) || source.is(DamageTypes.FLY_INTO_WALL) || source.is(DamageTypes.IN_WALL)) return CRUSHED;
            if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC) || source.is(DamageTypes.WITHER)
                    || source.is(DamageTypes.DRAGON_BREATH) || source.is(Consumables.SPICE_DAMAGE)) return SICK;
            return VIOLENT;
        }
        String detail = internal == null ? "" : internal;
        if (detail.startsWith("core_")) return VIOLENT;
        switch (detail) {
            case "old_age", "offering" -> { return NATURAL; }
            case "poison", "mold", "non_yukkuri", "infection", "toxin" -> { return SICK; }
            case "leak" -> { return VIOLENT; }
            case "burn" -> { return BURNED; }
            default -> { }
        }
        // 1.12 すっき死: the body spent itself and withers.
        if (sukkiriExhaustion) return STARVED;
        return UNKNOWN;
    }

    private static boolean generic(DamageSource source) {
        return source.is(DamageTypes.GENERIC) || source.is(DamageTypes.GENERIC_KILL);
    }

    /** The stored category; remains made before categories existed read as unknown. */
    public static String of(CompoundTag remains) {
        String value = remains.getString(CATEGORY);
        return value.isEmpty() ? UNKNOWN : value;
    }

    /** The visual form of a category; unknown and natural deaths share the quiet form. */
    public static String form(String category) {
        return category.equals(UNKNOWN) ? NATURAL : category;
    }
}
