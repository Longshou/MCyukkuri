package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * One size for a Marisa's hat in every form, worn, held, set down or capsized (design Q3, decision R6): her growth times the
 * factor of her kind in hat_vessel.json {@code scale} (aquatic_marisa 1.4, every other kind 1), the multiplier the aquatic
 * hat always had. It reads only synced facts (kind flags, growth), so the hat drawn on the client and the hat entity agree.
 */
public final class HatScale {
    private static final Map<SubspeciesLife.Kind, Float> FACTORS = read();

    private HatScale() {}

    private static Map<SubspeciesLife.Kind, Float> read() {
        var out = new EnumMap<SubspeciesLife.Kind, Float>(SubspeciesLife.Kind.class);
        var table = HatGeometry.scales();
        for (var kind : SubspeciesLife.Kind.values()) {
            var key = kind.name().toLowerCase(Locale.ROOT);
            if (table != null && table.has(key)) out.put(kind, table.get(key).getAsFloat());
        }
        return out;
    }

    public static float factor(SubspeciesLife.Kind kind) { return FACTORS.getOrDefault(kind, 1f); }

    /** The factor for synced or saved subspecies flags (the kind is their low four bits). */
    public static float factor(int subspeciesFlags) {
        var kinds = SubspeciesLife.Kind.values();
        int kind = subspeciesFlags & 15;
        return kind < kinds.length ? factor(kinds[kind]) : 1;
    }

    /** The hat's size relative to the worn standard of a full-grown ordinary Marisa. */
    public static float of(YukkuriView owner) { return of(owner.growthScale(), owner.subspeciesFlags()); }

    /** The same for a hat away from its owner (an item): the size it was taken at and the kind it belongs to. */
    public static float of(float growth, int subspeciesFlags) { return growth * factor(subspeciesFlags); }
}
