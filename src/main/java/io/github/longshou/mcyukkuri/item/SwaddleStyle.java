package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Styles of the one cloth swaddle item (docs/PET_SHOP.md, 布襁褓款式). The style lives in the stack's custom data under {@link #TAG}; a stack
 * without it is the plain cream knit. Styles only change the look (item icon, CareWearRenderer); warmth, calming and age limits are the
 * same for all. The order is the wear-bit index shared with the client and must not be reordered.
 */
public enum SwaddleStyle {
    /** Cream knit with a rolled cuff and a crossover flap (project design). */
    KNIT("knit"),
    /** Pink bag with a lace frill and a bow (EH30). */
    FRILL("frill"),
    /** Red bag with a frilled collar and the wearer's initial (EH34 p.7-8). */
    LETTER("letter"),
    /** Tiger-striped lower piece with the tiger mark (EH34 p.12-14). */
    TIGER("tiger"),
    /** Outing wear with a rubber sole, strap and pouch (EH31 p.2). */
    OUTING("outing"),
    /** Thick winter fleece with pompom ties (N91's cold-season wear; the shape is project design). */
    FLEECE("fleece"),
    /** Blue gingham with a crossover flap (project design). */
    GINGHAM("gingham"),
    /** Small-flower print with a crossover flap (project design). */
    FLORAL("floral");

    public static final String TAG = "SwaddleStyle";
    private static final SwaddleStyle[] VALUES = values();
    public final String id;

    SwaddleStyle(String id) { this.id = id; }

    public static SwaddleStyle byId(String id) {
        for (var style : VALUES) if (style.id.equals(id)) return style;
        return KNIT;
    }
    public static SwaddleStyle of(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? KNIT : byId(data.copyTag().getString(TAG));
    }
    public static SwaddleStyle random(RandomSource random) { return VALUES[random.nextInt(VALUES.length)]; }

    /** Writes this style into the stack; the knit is the plain item and carries no tag, so it stacks with crafted ones. */
    public ItemStack apply(ItemStack stack) {
        if (this == KNIT) CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(TAG));
        else CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(TAG, id));
        return stack;
    }
    public ItemStack stack() { return apply(new ItemStack(ModItems.SWADDLE_CLOTH.get())); }
}
